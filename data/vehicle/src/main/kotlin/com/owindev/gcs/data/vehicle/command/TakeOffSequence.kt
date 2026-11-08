package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.data.vehicle.state.ArduCopterFlightModes
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ArduCopter takes off only in a mode that allows it, GUIDED here (`handle_MAV_CMD_NAV_TAKEOFF`, Copter-4.7.2), and an
 * ACCEPTED mode change is not yet the mode: NAV_TAKEOFF waits for a HEARTBEAT showing GUIDED (REQ-044). Each step is
 * sent only while the HEARTBEAT shows the vehicle armed and both its landed state and its position say it is on the
 * ground, checked with the route under the sender's lock: a mode change would abort a flying vehicle's landing or
 * return before ArduCopter refused the take off (#112).
 */
internal class TakeOffSequence @Inject constructor(
    private val exchange: CommandExchange,
    private val vehicles: VehicleRepository,
    private val routes: CommandRoutes,
    private val telemetry: TelemetryStore,
    private val landedRule: LandedRule,
    private val groundRule: GroundPositionRule,
) {

    private val takeOffRoutes = CommandRoutes(::takeOffRouteTo)

    suspend fun run(
        systemId: SystemId,
        altitudeMetres: Float,
        onState: (PendingState) -> Unit,
        onResult: (CommandResult) -> Unit,
    ) {
        if (flightModeOf(systemId) != GUIDED && !changeToGuided(systemId, onState, onResult)) return
        exchange.run(systemId, TAKE_OFF, takeOffCommand(altitudeMetres), onState, takeOffRoutes, onResult)
    }

    /** Whether the vehicle is in GUIDED now; when not, [onResult] has been told why. */
    private suspend fun changeToGuided(
        systemId: SystemId,
        onState: (PendingState) -> Unit,
        onResult: (CommandResult) -> Unit,
    ): Boolean {
        var accepted = false
        exchange.run(systemId, MODE_CHANGE, GUIDED_MODE_CHANGE, onState, takeOffRoutes) { result ->
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
        if (guided == null) {
            val route = takeOffRouteTo(systemId)
            onResult(
                if (route is CommandRoute.Closed) {
                    CommandResult.NotSent(route.reason)
                } else {
                    CommandResult.ModeNotChanged(modeChange = null)
                },
            )
        }
        return guided != null
    }

    private fun takeOffRouteTo(systemId: SystemId): CommandRoute {
        val route = routes.routeTo(systemId)
        if (route !is CommandRoute.Open) return route
        val telemetry = telemetry.telemetry.value[systemId]
        return when {
            vehicles.vehicles.value[systemId]?.state?.armed != true -> CommandRoute.Closed(NotSentReason.NOT_ARMED)

            !landedRule.isOnGround(telemetry) || !groundRule.isNearGround(telemetry) ->
                CommandRoute.Closed(NotSentReason.NOT_LANDED)

            else -> route
        }
    }

    private fun flightModeOf(systemId: SystemId): FlightMode? = vehicles.vehicles.value[systemId]?.state?.flightMode

    companion object {
        /**
         * Two HEARTBEAT periods (1 Hz) and some margin. With the mode change's ACK timeout and NAV_TAKEOFF's, the
         * sequence takes at most 5.5 s. ArduCopter disarms a copter armed on the ground that has not taken off after
         * `DISARM_DELAY`, counted from arming: 10 s by default (`AUTO_DISARMING_DELAY`, Copter-4.7.2) and read as 10
         * in SITL (#99), so a take off started more than 4.5 s after arming can find the vehicle disarmed and end as
         * not armed.
         */
        val GUIDED_CONFIRM_TIMEOUT: Duration = 2.5.seconds

        private val GUIDED = ArduCopterFlightModes.flightModeOf(ArduCopterFlightModes.GUIDED)
        private val TAKE_OFF = VehicleCommand.TAKE_OFF.name
        private val MODE_CHANGE = "${VehicleCommand.TAKE_OFF.name} (mode change)"
    }
}
