package com.owindev.gcs.data.vehicle.parameter

import android.util.Log
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the active vehicle's [BatteryParameter]s each time it becomes CONNECTED or reboots while CONNECTED (REQ-028),
 * then logs each change of them for as long as it stays so.
 *
 * `BATT_MONITOR` is read first: at 0 there is no battery monitor, so no battery failsafe, and ArduPilot drops the rest
 * of its group from its parameter list (`AP_PARAM_FLAG_ENABLE`, `AP_Param.h`) while still answering each by name with
 * a value it does not use (SITL, 4.7.3), so nothing else is asked; nor while it is unknown. The others are asked one at
 * a time, skipping any value the vehicle sent during the round. Each request
 * is one PARAM_REQUEST_READ by name to the autopilot (`MAV_COMP_ID_AUTOPILOT1`); its answer is read from the
 * [ParameterStore], which the vehicle repository's listener fills, never from the link, so waiting never opens the
 * socket again (#39). A request unanswered within [ANSWER_TIMEOUT] is sent again, [MAX_ATTEMPTS] times in all, since a
 * PARAM_VALUE can be lost (mavlink.io `services/parameter.html`); a parameter still unanswered stays unknown until the
 * next round.
 *
 * A round stops, leaving the rest unknown, as soon as the vehicle is not heard right now (the `isHeard` given to [run])
 * or a request cannot be sent; the next CONNECTED starts a new one.
 *
 * Known limits:
 * - `BATT_MONITOR` takes effect only after the vehicle's reboot (`@RebootRequired`), both ways: raised from 0, the
 *   other values are read by the round that reboot starts; lowered to 0 from another GCS, the store shows no monitor
 *   at once while the vehicle still monitors, and acts, until it reboots;
 * - values are kept while the vehicle is LOST, as last read: whoever shows them must combine them with the link state;
 * - PARAM_VALUE is unsigned until v0.6, so a forged one can set a value (`docs/security.md`, K-2 and N-2).
 */
@Singleton
internal class BatteryParameterRequester @Inject constructor(
    private val sender: GcsMessageSender,
    private val store: ParameterStore,
) {

    /**
     * Reads every parameter each time [targets] gives a vehicle, then logs their changes until the next one. `null`
     * means no vehicle to ask; whatever is in progress is cancelled then.
     */
    suspend fun run(targets: Flow<VehicleTarget?>, isHeard: () -> Boolean) {
        targets.collectLatest { target ->
            if (target != null) {
                readAll(target, isHeard)
                logChanges(target)
            }
        }
    }

    private suspend fun readAll(target: VehicleTarget, isHeard: () -> Boolean) {
        // A value kept from before may have changed while the vehicle was not heard, and the store only publishes a
        // change, so an answer with the same value would never show: each round starts from nothing read.
        store.forget(target.systemId)
        val monitor = read(target, BatteryParameter.MONITOR, isHeard)
        if (monitor != Outcome.ANSWERED || valueOf(target, BatteryParameter.MONITOR) == NO_MONITOR) return
        for (parameter in BatteryParameter.entries - BatteryParameter.MONITOR) {
            if (read(target, parameter, isHeard) == Outcome.STOPPED) return
        }
    }

    private suspend fun read(target: VehicleTarget, parameter: BatteryParameter, isHeard: () -> Boolean): Outcome {
        var outcome = Outcome.UNANSWERED
        var attempts = 0
        while (outcome == Outcome.UNANSWERED && attempts < MAX_ATTEMPTS) {
            outcome = attempt(target, parameter, isHeard)
            attempts++
        }
        return outcome
    }

    private suspend fun attempt(target: VehicleTarget, parameter: BatteryParameter, isHeard: () -> Boolean) = when {
        valueOf(target, parameter) != null -> Outcome.ANSWERED
        !isHeard() || sender.send(requestOf(target, parameter), target.address) != SendResult.Sent -> Outcome.STOPPED
        answered(target, parameter) -> Outcome.ANSWERED
        else -> Outcome.UNANSWERED
    }

    private suspend fun answered(target: VehicleTarget, parameter: BatteryParameter): Boolean =
        withTimeoutOrNull(ANSWER_TIMEOUT) {
            store.parameters.first { followed -> followed.valueOf(target, parameter) != null }
        } != null

    private suspend fun logChanges(target: VehicleTarget) {
        store.parameters
            .map { followed -> BatteryParameter.entries.associateWith { followed.valueOf(target, it) } }
            .distinctUntilChanged()
            .collect { values ->
                val line = values.entries.joinToString(" ") { (parameter, value) ->
                    "${parameter.id}=${value ?: UNKNOWN}"
                }
                Log.i(TAG, "Battery parameters: $line")
            }
    }

    private fun valueOf(target: VehicleTarget, parameter: BatteryParameter): Float? =
        store.parameters.value.valueOf(target, parameter)

    private fun FollowedParameters.valueOf(target: VehicleTarget, parameter: BatteryParameter): Float? =
        if (systemId == target.systemId) values[parameter] else null

    private fun requestOf(target: VehicleTarget, parameter: BatteryParameter) = ParamRequestRead(
        targetSystem = target.systemId.value,
        targetComponent = MAV_COMP_ID_AUTOPILOT1,
        paramId = parameter.id,
    )

    private enum class Outcome { ANSWERED, UNANSWERED, STOPPED }

    companion object {
        /** How long to wait for each PARAM_VALUE before asking again; SITL answered within 9–122 ms (#101). */
        val ANSWER_TIMEOUT: Duration = 1.seconds

        /** Requests sent for one parameter in a round, the first included. */
        const val MAX_ATTEMPTS = 3

        /** `BATT_MONITOR` 0, "Disabled" (`AP_BattMonitor_Params.cpp`). */
        private const val NO_MONITOR = 0f

        /** `MAV_COMP_ID_AUTOPILOT1` (`minimal.xml`). */
        private const val MAV_COMP_ID_AUTOPILOT1 = 1
        private const val UNKNOWN = "?"
        private const val TAG = "BatteryParameters"
    }
}
