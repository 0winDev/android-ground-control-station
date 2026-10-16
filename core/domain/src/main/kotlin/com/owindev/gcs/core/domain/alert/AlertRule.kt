package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.isFix3d
import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * Which alerts are active for the active vehicle's [Telemetry] (REQ-028).
 *
 * Alerts are state: [evaluate] takes the alerts active until now and returns those active now, so that an alert
 * keeps its acknowledgement while it lasts and the battery thresholds can have hysteresis. A value that is stale
 * or not known (missing, or unknown to the vehicle) never raises or clears an alert as if it were current: the
 * alerts that depend on it stay as they were, and a stale value raises [Alert.TelemetryStale] for its kind instead.
 * A battery that is stale or not known is also [Alert.BatteryUnknown], so it never looks OK.
 *
 * The result is ordered by level, most urgent first, and newest first within a level.
 */
class AlertRule(
    private val staleness: StalenessRule = StalenessRule(),
    private val thresholds: BatteryThresholds = BatteryThresholds(),
) {

    /**
     * The alerts active now, from those [previous]ly active and the [telemetry] as it is now, for a vehicle whose
     * alerts have been evaluated since [evaluatingSince]. Each alert raised now takes the next number from
     * [nextOccurrence], which must never give the same one twice.
     *
     * A battery or GPS never received is unknown, never OK, but only once the vehicle has been evaluated for that
     * value's staleness threshold: until then it has had no time to arrive, as when the link has just started and
     * its streams were just requested, and an alert raised then would be a false one.
     */
    fun evaluate(
        previous: List<ActiveAlert>,
        telemetry: Telemetry,
        evaluatingSince: TimeMark,
        nextOccurrence: () -> Long,
    ): List<ActiveAlert> {
        val wasActive = previous.map(ActiveAlert::alert).toSet()
        val active = batteryAlerts(telemetry.battery, evaluatingSince, wasActive) +
            gpsAlerts(telemetry.gps, evaluatingSince, wasActive) +
            staleAlerts(telemetry)
        val kept = previous.filter { it.alert in active }
        val raised = (active - wasActive).map { ActiveAlert(it, occurrence = nextOccurrence()) }
        return (raised + kept).sortedByDescending { it.alert.level }
    }

    /**
     * When the alerts must be evaluated again with nothing new arriving: when a value goes stale
     * ([StalenessRule.untilNextStale]), or a value that stopped arriving would never raise [Alert.TelemetryStale],
     * and when a value never received has had its time to arrive ([evaluate]).
     */
    fun untilNextEvaluation(telemetry: Telemetry, evaluatingSince: TimeMark): Duration? {
        val untilNeverReceived = NEVER_RECEIVED_ALERTED
            .filter { kind -> telemetry[kind] == null }
            .map { kind -> staleness.thresholdOf(kind) - evaluatingSince.elapsedNow() }
            .filter { it > Duration.ZERO }
        return (untilNeverReceived + listOfNotNull(staleness.untilNextStale(telemetry))).minOrNull()
    }

    /**
     * [active] with the alert of [occurrence] acknowledged, unless it is an emergency: an emergency is never
     * acknowledged. An occurrence that is no longer active acknowledges nothing, even if its alert was raised again.
     */
    fun acknowledge(active: List<ActiveAlert>, occurrence: Long): List<ActiveAlert> = active.map {
        if (it.occurrence == occurrence && it.alert.level != AlertLevel.EMERGENCY) it.copy(acknowledged = true) else it
    }

    private fun batteryAlerts(timed: Timed<Battery>?, evaluatingSince: TimeMark, wasActive: Set<Alert>): Set<Alert> =
        when {
            // Nothing received yet is unknown, never OK, once it has had its time to arrive.
            timed == null && isLate(TelemetryKind.BATTERY, evaluatingSince) -> setOf(Alert.BatteryUnknown)

            timed == null -> wasActive intersect BATTERY_ALERTS

            // What it said last may no longer be true: unknown, keeping what it raised until it is heard again.
            isStale(timed, TelemetryKind.BATTERY) -> (wasActive intersect BATTERY_ALERTS) + Alert.BatteryUnknown

            else -> freshBatteryAlerts(timed.value, wasActive)
        }

    private fun freshBatteryAlerts(battery: Battery, wasActive: Set<Alert>): Set<Alert> = buildSet {
        val remaining = battery.remaining
        if (!battery.monitored || battery.voltage == null || remaining == null) add(Alert.BatteryUnknown)
        if (battery.monitored && !battery.healthy) add(Alert.BatteryUnhealthy)
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

    private fun staleAlerts(telemetry: Telemetry): Set<Alert> = TelemetryKind.entries
        .filter { kind -> telemetry[kind]?.let { isStale(it, kind) } == true }
        .mapTo(mutableSetOf(), Alert::TelemetryStale)

    private fun isStale(timed: Timed<*>, kind: TelemetryKind): Boolean =
        staleness.isStale(kind, timed.receivedAt.elapsedNow())

    /** A value of [kind] never received is late once the vehicle has been evaluated for its staleness threshold. */
    private fun isLate(kind: TelemetryKind, evaluatingSince: TimeMark): Boolean =
        staleness.isStale(kind, evaluatingSince.elapsedNow())

    private companion object {
        /** The values that raise an alert when never received, once they are late ([evaluate]). */
        val NEVER_RECEIVED_ALERTED: Set<TelemetryKind> = setOf(TelemetryKind.BATTERY, TelemetryKind.GPS)

        val ENERGY_ALERTS: Set<Alert> = setOf(Alert.BatteryCritical, Alert.BatteryLow)
        val BATTERY_ALERTS: Set<Alert> = ENERGY_ALERTS + setOf(Alert.BatteryUnhealthy, Alert.BatteryUnknown)
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
