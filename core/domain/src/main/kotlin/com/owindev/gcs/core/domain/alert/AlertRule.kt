package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.isFix3d
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Which alerts are active for the active vehicle's [Telemetry] (REQ-028).
 *
 * Alerts are state: [evaluate] takes the [AlertState] until now and returns the one now, so that an alert keeps its
 * acknowledgement while it lasts and the battery thresholds can have hysteresis and a debounce. A value that is stale
 * or not known (missing, or unknown to the vehicle) never raises or clears an alert as if it were current: the
 * alerts that depend on it stay as they were, and a stale value raises [Alert.TelemetryStale] for its kind instead.
 * A battery that is stale or not known is also [Alert.BatteryUnknown], so it never looks OK.
 *
 * The battery is judged at the vehicle's own thresholds where it has set them ([batteryAlertLimitsOf]), and at
 * [thresholds] for any energy one it has not.
 *
 * The active alerts are ordered by level, most urgent first, and newest first within a level. [timeSource] marks when
 * each alert is raised; it must be the one the telemetry is marked with.
 */
class AlertRule(
    private val staleness: StalenessRule = StalenessRule(),
    thresholds: GcsEnergyThresholds = GcsEnergyThresholds(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {

    private val batteryRule = BatteryAlertRule(thresholds, timeSource)

    /**
     * The state now, from the one [previous]ly and the [input] as it is now. Each alert raised now takes the next
     * number from [nextOccurrence], which must never give the same one twice.
     *
     * A battery or GPS never received is unknown, never OK, but only once the vehicle has been evaluated for that
     * value's staleness threshold: until then it has had no time to arrive, as when the link has just started and
     * its streams were just requested, and an alert raised then would be a false one. How a fresh battery is judged
     * is [BatteryAlertRule]'s; while it is stale or missing, each voltage debounce starts again.
     */
    fun evaluate(previous: AlertState, input: AlertInput, nextOccurrence: () -> Long): AlertState {
        val telemetry = input.telemetry
        val wasActive = previous.active.map(ActiveAlert::alert).toSet()
        val judged = batteryAlerts(input, previous)
        val limits = batteryRule.limitsNotRead(input.battery, previous.limitsUnreadSince)
        val active = judged.alerts +
            setOfNotNull(Alert.BatteryLimitsNotRead.takeIf { limits.raised }) +
            gpsAlerts(telemetry.gps, input.evaluatingSince, wasActive) +
            staleAlerts(telemetry, previous.active)
        val kept = previous.active.filter { it.alert in active }
        val now = timeSource.markNow()
        val raised = (active - wasActive).map {
            ActiveAlert(
                alert = it,
                occurrence = nextOccurrence(),
                raisedAt = now,
                origin = telemetry.origin,
            )
        }
        return AlertState(
            active = (raised + kept).sortedByDescending { it.alert.level },
            lowVoltageSince = judged.lowVoltageSince,
            criticalVoltageSince = judged.criticalVoltageSince,
            limitsUnreadSince = limits.since,
        )
    }

    /**
     * When the [state] must be evaluated again with nothing new arriving: when a value goes stale
     * ([StalenessRule.untilNextStale]), or a value that stopped arriving would never raise [Alert.TelemetryStale];
     * when a value never received has had its time to arrive ([evaluate]); when a stale alert's hold ends, or it
     * would outlast [STALE_MINIMUM_HOLD]; when a voltage debounce ends, or a voltage that stays across its
     * threshold would wait for the next reading; and when battery settings not read are due.
     */
    fun untilNextEvaluation(state: AlertState, input: AlertInput): Duration? {
        val untilNeverReceived = NEVER_RECEIVED_ALERTED
            .filter { kind -> input.telemetry[kind] == null }
            .map { kind -> staleness.thresholdOf(kind) - input.evaluatingSince.elapsedNow() }
        val untilHoldEnds = state.active
            .filter { it.alert is Alert.TelemetryStale }
            .map { STALE_MINIMUM_HOLD - it.raisedAt.elapsedNow() }
        val untilLimitsDue =
            listOfNotNull(state.limitsUnreadSince?.let { BATTERY_LIMITS_DUE - it.elapsedNow() })
        val untilStale = listOfNotNull(staleness.untilNextStale(input.telemetry)) + untilLimitsDue
        return (untilNeverReceived + untilHoldEnds + batteryRule.untilDebounceEnds(state, input.battery) + untilStale)
            .filter { it > Duration.ZERO }
            .minOrNull()
    }

    /**
     * [active] with the alert of [occurrence] acknowledged, unless it is an emergency: an emergency is never
     * acknowledged. An occurrence that is no longer active acknowledges nothing, even if its alert was raised again.
     */
    fun acknowledge(active: List<ActiveAlert>, occurrence: Long): List<ActiveAlert> = active.map {
        if (it.occurrence == occurrence && it.alert.level != AlertLevel.EMERGENCY) it.copy(acknowledged = true) else it
    }

    private fun batteryAlerts(input: AlertInput, previous: AlertState): BatteryJudgement {
        val timed = input.telemetry.battery
        val wasActive = previous.active.map(
            ActiveAlert::alert,
        ).filterTo(mutableSetOf(), BatteryAlertRule::isBatteryAlert)
        return when {
            // Nothing received yet is unknown, never OK, once it has had its time to arrive.
            timed == null && isLate(TelemetryKind.BATTERY, input.evaluatingSince) ->
                BatteryJudgement(setOf(Alert.BatteryUnknown))

            timed == null -> BatteryJudgement(wasActive)

            // What it said last may no longer be true: unknown, keeping what it raised until it is heard again.
            isStale(timed, TelemetryKind.BATTERY) -> BatteryJudgement(wasActive + Alert.BatteryUnknown)

            else -> batteryRule.alertsOf(timed.value, input, previous, wasActive)
        }
    }

    private fun gpsAlerts(timed: Timed<GpsStatus>?, evaluatingSince: TimeMark, wasActive: Set<Alert>): Set<Alert> =
        when {
            // Nothing received yet is no fix, never a fix, once it has had its time to arrive.
            timed == null && isLate(TelemetryKind.GPS, evaluatingSince) -> setOf(Alert.GpsFixLost)

            timed == null -> wasActive intersect setOf(Alert.GpsFixLost)

            isStale(timed, TelemetryKind.GPS) -> wasActive intersect setOf(Alert.GpsFixLost)

            timed.value.fixType.isFix3d -> emptySet()

            else -> setOf(Alert.GpsFixLost)
        }

    /**
     * A stale alert for each stale stream, plus those raised less than [STALE_MINIMUM_HOLD] ago, which stay even if
     * their stream is fresh again.
     */
    private fun staleAlerts(telemetry: Telemetry, previous: List<ActiveAlert>): Set<Alert> {
        val stale = TelemetryKind.DISPLAYED
            .filter { kind -> telemetry[kind]?.let { isStale(it, kind) } == true }
            .mapTo(mutableSetOf<Alert>(), Alert::TelemetryStale)
        val held = previous
            .filter { it.alert is Alert.TelemetryStale && it.raisedAt.elapsedNow() < STALE_MINIMUM_HOLD }
            .map(ActiveAlert::alert)
        return stale + held
    }

    private fun isStale(timed: Timed<*>, kind: TelemetryKind): Boolean =
        staleness.isStale(kind, timed.receivedAt.elapsedNow())

    /** A value of [kind] never received is late once the vehicle has been evaluated for its staleness threshold. */
    private fun isLate(kind: TelemetryKind, evaluatingSince: TimeMark): Boolean =
        staleness.isStale(kind, evaluatingSince.elapsedNow())

    companion object {
        /**
         * How long an [Alert.TelemetryStale] lasts at least once raised, so a stream late for an instant does not
         * raise and clear it repeatedly (#31). It ends at most this long after the stream is fresh again. In about
         * 6 min of steady SITL streams (#41) one ATTITUDE stream was late once, for about 0.15 s.
         */
        val STALE_MINIMUM_HOLD: Duration = 2.seconds

        /**
         * How long the vehicle's battery settings may stay not read before [Alert.BatteryLimitsNotRead] is raised:
         * longer than a whole round of `BatteryParameterRequester` that still succeeds (eleven parameters, three
         * requests each, 1 s apart: 33 s), so an answer to a last request never makes it flash.
         */
        val BATTERY_LIMITS_DUE: Duration = 40.seconds

        /** The values that raise an alert when never received, once they are late ([evaluate]). */
        private val NEVER_RECEIVED_ALERTED: Set<TelemetryKind> = setOf(TelemetryKind.BATTERY, TelemetryKind.GPS)
    }
}

/**
 * The GCS's own remaining energy thresholds, in percent, at or below which [Alert.BatteryLow] and
 * [Alert.BatteryCritical] are raised, and how many points above a threshold the energy must come back before they
 * clear, so an alert does not flap at it.
 *
 * The low threshold follows ArduPilot's battery failsafe, which recommends `BATT_LOW_MAH` at 20 % of the
 * battery's capacity (https://ardupilot.org/copter/docs/failsafe-battery.html); the documentation gives no
 * figure for the critical one, so 10 % is ours.
 *
 * They apply to a level only while the vehicle's `BATT_LOW_MAH` or `BATT_CRT_MAH` is not read or not set
 * ([EnergyLimitSource]); the hysteresis applies to the vehicle's too.
 */
class GcsEnergyThresholds(val low: Int = 20, val critical: Int = 10, val hysteresis: Int = 3) {

    init {
        require(critical in 0 until low) { "The critical threshold must be from 0 to below the low one, was $critical" }
        require(hysteresis > 0) { "The hysteresis must be positive, was $hysteresis" }
        require(low + hysteresis <= PERCENT_MAX) { "The low threshold plus its hysteresis must not pass 100 %" }
    }

    private companion object {
        const val PERCENT_MAX = 100
    }
}
