package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.Battery
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
 * Alerts are state: [evaluate] takes the alerts active until now and returns those active now, so that an alert
 * keeps its acknowledgement while it lasts and the battery thresholds can have hysteresis. A value that is stale
 * or not known (missing, or unknown to the vehicle) never raises or clears an alert as if it were current: the
 * alerts that depend on it stay as they were, and a stale value raises [Alert.TelemetryStale] for its kind instead.
 * A battery that is stale or not known is also [Alert.BatteryUnknown], so it never looks OK.
 *
 * The result is ordered by level, most urgent first, and newest first within a level. [timeSource] marks when each
 * alert is raised; it must be the one the telemetry is marked with.
 */
class AlertRule(
    private val staleness: StalenessRule = StalenessRule(),
    private val thresholds: BatteryThresholds = BatteryThresholds(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {

    /**
     * The alerts active now, from those [previous]ly active and the [telemetry] as it is now, for a vehicle that is
     * [armed] or not and whose alerts have been evaluated since [evaluatingSince]. Each alert raised now takes the
     * next number from [nextOccurrence], which must never give the same one twice.
     *
     * A battery or GPS never received is unknown, never OK, but only once the vehicle has been evaluated for that
     * value's staleness threshold: until then it has had no time to arrive, as when the link has just started and
     * its streams were just requested, and an alert raised then would be a false one.
     *
     * A battery monitor that is not healthy is an emergency ([Alert.BatteryUnhealthy]), except while the vehicle is
     * disarmed and the monitor has no voltage reading: ArduPilot can send that at boot, before the monitor has read
     * anything (`truncated/sys_status.bin`, enabled, not healthy, 0 mV), and it is then only unknown
     * ([Alert.BatteryUnknown]). Armed, a monitor that fails is always an emergency, reading or not, and it stays one
     * after disarming: only a monitor that was not already an emergency is played down, so the rule covers the boot
     * and never a failure seen in flight. On ArduPilot's battery failsafe the voltage keeps arriving (#41), so it
     * stays an emergency armed or not.
     */
    fun evaluate(
        previous: List<ActiveAlert>,
        telemetry: Telemetry,
        armed: Boolean,
        evaluatingSince: TimeMark,
        nextOccurrence: () -> Long,
    ): List<ActiveAlert> {
        val wasActive = previous.map(ActiveAlert::alert).toSet()
        val active = batteryAlerts(telemetry.battery, armed, evaluatingSince, wasActive) +
            gpsAlerts(telemetry.gps, evaluatingSince, wasActive) +
            staleAlerts(telemetry, previous)
        val kept = previous.filter { it.alert in active }
        val now = timeSource.markNow()
        val raised = (active - wasActive).map {
            ActiveAlert(
                alert = it,
                occurrence = nextOccurrence(),
                raisedAt = now,
                origin = telemetry.origin,
            )
        }
        return (raised + kept).sortedByDescending { it.alert.level }
    }

    /**
     * When the [active] alerts must be evaluated again with nothing new arriving: when a value goes stale
     * ([StalenessRule.untilNextStale]), or a value that stopped arriving would never raise [Alert.TelemetryStale];
     * when a value never received has had its time to arrive ([evaluate]); and when a stale alert's hold ends, or it
     * would outlast [STALE_MINIMUM_HOLD].
     */
    fun untilNextEvaluation(active: List<ActiveAlert>, telemetry: Telemetry, evaluatingSince: TimeMark): Duration? {
        val untilNeverReceived = NEVER_RECEIVED_ALERTED
            .filter { kind -> telemetry[kind] == null }
            .map { kind -> staleness.thresholdOf(kind) - evaluatingSince.elapsedNow() }
        val untilHoldEnds = active
            .filter { it.alert is Alert.TelemetryStale }
            .map { STALE_MINIMUM_HOLD - it.raisedAt.elapsedNow() }
        return (untilNeverReceived + untilHoldEnds + listOfNotNull(staleness.untilNextStale(telemetry)))
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

    private fun batteryAlerts(
        timed: Timed<Battery>?,
        armed: Boolean,
        evaluatingSince: TimeMark,
        wasActive: Set<Alert>,
    ): Set<Alert> = when {
        // Nothing received yet is unknown, never OK, once it has had its time to arrive.
        timed == null && isLate(TelemetryKind.BATTERY, evaluatingSince) -> setOf(Alert.BatteryUnknown)

        timed == null -> wasActive intersect BATTERY_ALERTS

        // What it said last may no longer be true: unknown, keeping what it raised until it is heard again.
        isStale(timed, TelemetryKind.BATTERY) -> (wasActive intersect BATTERY_ALERTS) + Alert.BatteryUnknown

        else -> freshBatteryAlerts(timed.value, armed, wasActive)
    }

    private fun freshBatteryAlerts(battery: Battery, armed: Boolean, wasActive: Set<Alert>): Set<Alert> = buildSet {
        val remaining = battery.remaining
        if (!battery.monitored || battery.voltage == null || remaining == null) add(Alert.BatteryUnknown)
        // Disarmed with no reading yet, as at boot, an unhealthy monitor is only unknown (added above), unless it
        // was already an emergency: a failure seen in flight is not played down by landing.
        val failing = battery.monitored && !battery.healthy
        val playedDown = !armed && battery.voltage == null && Alert.BatteryUnhealthy !in wasActive
        if (failing && !playedDown) add(Alert.BatteryUnhealthy)
        // Without a monitor or a remaining energy, the energy alerts stay as they were. An unhealthy monitor still
        // drives them: ArduPilot sends no remaining energy (-1) when the monitor itself fails, so a percentage from
        // an unhealthy one is real, sent after the battery failsafe has triggered and while the battery goes on
        // draining (`battery_remaining_pct` in `libraries/GCS_MAVLink/GCS_Common.cpp`, which needs a healthy
        // monitor in `capacity_remaining_pct`, `libraries/AP_BattMonitor/AP_BattMonitor_Backend.cpp`).
        if (!battery.monitored || remaining == null) {
            addAll(wasActive intersect ENERGY_ALERTS)
        } else {
            energyAlert(remaining, wasActive)?.let(::add)
        }
    }

    /** Raised at or below a threshold, cleared only once the energy is [BatteryThresholds.hysteresis] above it. */
    private fun energyAlert(remaining: Int, wasActive: Set<Alert>): Alert? {
        val wasCritical = Alert.BatteryCritical in wasActive
        val wasLow = wasCritical || Alert.BatteryLow in wasActive
        return when {
            remaining <= thresholds.critical -> Alert.BatteryCritical
            wasCritical && remaining < thresholds.critical + thresholds.hysteresis -> Alert.BatteryCritical
            remaining <= thresholds.low -> Alert.BatteryLow
            wasLow && remaining < thresholds.low + thresholds.hysteresis -> Alert.BatteryLow
            else -> null
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

        /** The values that raise an alert when never received, once they are late ([evaluate]). */
        private val NEVER_RECEIVED_ALERTED: Set<TelemetryKind> = setOf(TelemetryKind.BATTERY, TelemetryKind.GPS)

        private val ENERGY_ALERTS: Set<Alert> = setOf(Alert.BatteryCritical, Alert.BatteryLow)
        private val BATTERY_ALERTS: Set<Alert> = ENERGY_ALERTS + setOf(Alert.BatteryUnhealthy, Alert.BatteryUnknown)
    }
}

/**
 * The battery's remaining energy, in percent, at or below which [Alert.BatteryLow] and [Alert.BatteryCritical]
 * are raised, and how many points above it the energy must come back before they clear, so an alert does not
 * flap at the threshold.
 *
 * The low threshold follows ArduPilot's battery failsafe, which recommends `BATT_LOW_MAH` at 20 % of the
 * battery's capacity (https://ardupilot.org/copter/docs/failsafe-battery.html); the documentation gives no
 * figure for the critical one, so 10 % is ours.
 *
 * Known limit: only the remaining energy is judged, while ArduPilot's failsafe also triggers on voltage
 * (`BATT_LOW_VOLT`, `BATT_CRT_VOLT`). A monitor without current sensing, or a `BATT_CAPACITY` of 10 mAh or less,
 * sends no remaining energy, so the battery is only [Alert.BatteryUnknown] and never low or critical; and these
 * thresholds are not the ones configured on the vehicle. A voltage rule from the vehicle's own parameters is #53.
 */
class BatteryThresholds(val low: Int = 20, val critical: Int = 10, val hysteresis: Int = 3) {

    init {
        require(critical in 0 until low) { "The critical threshold must be from 0 to below the low one, was $critical" }
        require(hysteresis > 0) { "The hysteresis must be positive, was $hysteresis" }
        require(low + hysteresis <= PERCENT_MAX) { "The low threshold plus its hysteresis must not pass 100 %" }
    }

    private companion object {
        const val PERCENT_MAX = 100
    }
}
