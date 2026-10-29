package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.data.vehicle.state.ArduCopterFlightModes
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ArduCopter takes off only in a mode that allows it, GUIDED here (`handle_MAV_CMD_NAV_TAKEOFF`, Copter-4.7.2), and an
 * ACCEPTED mode change is not yet the mode: NAV_TAKEOFF waits for a HEARTBEAT showing GUIDED (REQ-044). Each step is
 * sent only while that HEARTBEAT shows the vehicle armed, checked with the route under the sender's lock.
 */
internal class TakeOffSequence @Inject constructor(
    private val exchange: CommandExchange,
    private val vehicles: VehicleRepository,
    private val routes: CommandRoutes,
) {

    private val armedRoutes = CommandRoutes(::armedRouteTo)

    suspend fun run(
        systemId: SystemId,
        altitudeMetres: Float,
        onState: (PendingState) -> Unit,
        onResult: (CommandResult) -> Unit,
    ) {
        if (flightModeOf(systemId) != GUIDED && !changeToGuided(systemId, onState, onResult)) return
        exchange.run(systemId, TAKE_OFF, takeOffCommand(altitudeMetres), onState, armedRoutes, onResult)
    }

    /** Whether the vehicle is in GUIDED now; when not, [onResult] has been told why. */
    private suspend fun changeToGuided(
        systemId: SystemId,
        onState: (PendingState) -> Unit,
        onResult: (CommandResult) -> Unit,
    ): Boolean {
        var accepted = false
        exchange.run(systemId, MODE_CHANGE, GUIDED_MODE_CHANGE, onState, armedRoutes) { result ->
            accepted = result == CommandResult.Accepted
            if (!accepted) {
                onResult(
                    if (result is CommandResult.NotSent) result else CommandResult.ModeNotChanged(result),
                )
            }
        }
        if (!accepted) return false
        val guided = withTimeoutOrNull(GUIDED_CONFIRM_TIMEOUT) {
            vehicles.vehicles.first { it[systemId]?.state?.flightMode == GUIDED }
        }
        if (guided == null) onResult(CommandResult.ModeNotChanged(modeChange = null))
        return guided != null
    }

    private fun armedRouteTo(systemId: SystemId): CommandRoute {
        val route = routes.routeTo(systemId)
        val armed = vehicles.vehicles.value[systemId]?.state?.armed == true
        return if (route is CommandRoute.Open && !armed) CommandRoute.Closed(NotSentReason.NOT_ARMED) else route
    }

    private fun flightModeOf(systemId: SystemId): FlightMode? = vehicles.vehicles.value[systemId]?.state?.flightMode

    companion object {
        /**
         * Two HEARTBEAT periods (1 Hz) and some margin. With the mode change's ACK timeout and NAV_TAKEOFF's, the
         * sequence takes at most 5.5 s, which must stay below ArduCopter's `DISARM_DELAY`, after which an armed copter
         * that has not taken off disarms by itself.
         */
        val GUIDED_CONFIRM_TIMEOUT: Duration = 2.5.seconds

        private val GUIDED = ArduCopterFlightModes.flightModeOf(ArduCopterFlightModes.GUIDED)
        private val TAKE_OFF = VehicleCommand.TAKE_OFF.name
        private val MODE_CHANGE = "${VehicleCommand.TAKE_OFF.name} (mode change)"
    }
}
