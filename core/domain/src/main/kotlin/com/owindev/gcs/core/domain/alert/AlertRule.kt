package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import kotlin.time.Duration

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

    /** The alerts active now, from those [previous]ly active and the [telemetry] as it is now. */
    fun evaluate(previous: List<ActiveAlert>, telemetry: Telemetry): List<ActiveAlert> {
        val wasActive = previous.map(ActiveAlert::alert).toSet()
        val active = batteryAlerts(telemetry.battery, wasActive) +
            gpsAlerts(telemetry.gps, wasActive) +
            staleAlerts(telemetry)
        val kept = previous.filter { it.alert in active }
        val raised = (active - wasActive).map(::ActiveAlert)
        return (raised + kept).sortedByDescending { it.alert.level }
    }

    /**
     * How long until a value of [telemetry] goes stale: the alerts must be evaluated again then, even if nothing
     * new arrives, or a value that stopped arriving would never raise [Alert.TelemetryStale]. `null` when no value
     * is fresh, so nothing will change until something arrives.
     */
    fun untilNextStale(telemetry: Telemetry): Duration? = TelemetryKind.entries
        .mapNotNull { kind -> telemetry[kind]?.let { staleness.thresholdOf(kind) - it.receivedAt.elapsedNow() } }
        .filter { it > Duration.ZERO }
        .minOrNull()

    /** [active] with [alert] acknowledged, unless it is an emergency: an emergency is never acknowledged. */
    fun acknowledge(active: List<ActiveAlert>, alert: Alert): List<ActiveAlert> = active.map {
        if (it.alert == alert && alert.level != AlertLevel.EMERGENCY) it.copy(acknowledged = true) else it
    }

    private fun batteryAlerts(timed: Timed<Battery>?, wasActive: Set<Alert>): Set<Alert> = when {
        // Nothing received yet is unknown, never OK.
        timed == null -> setOf(Alert.BatteryUnknown)

        // What it said last may no longer be true: unknown, keeping what it raised until it is heard again.
        isStale(timed, TelemetryKind.BATTERY) -> (wasActive intersect BATTERY_ALERTS) + Alert.BatteryUnknown

        else -> freshBatteryAlerts(timed.value, wasActive)
    }

    private fun freshBatteryAlerts(battery: Battery, wasActive: Set<Alert>): Set<Alert> = buildSet {
        val remaining = battery.remaining
        if (!battery.monitored || remaining == null) add(Alert.BatteryUnknown)
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

    private fun gpsAlerts(timed: Timed<GpsStatus>?, wasActive: Set<Alert>): Set<Alert> = when {
        timed == null || isStale(timed, TelemetryKind.GPS) -> wasActive intersect setOf(Alert.GpsFixLost)
        timed.value.fixType in FIX_3D -> emptySet()
        else -> setOf(Alert.GpsFixLost)
    }

    private fun staleAlerts(telemetry: Telemetry): Set<Alert> = TelemetryKind.entries
        .filter { kind -> telemetry[kind]?.let { isStale(it, kind) } == true }
        .mapTo(mutableSetOf(), Alert::TelemetryStale)

    private fun isStale(timed: Timed<*>, kind: TelemetryKind): Boolean =
        staleness.isStale(kind, timed.receivedAt.elapsedNow())

    private companion object {
        val ENERGY_ALERTS: Set<Alert> = setOf(Alert.BatteryCritical, Alert.BatteryLow)
        val BATTERY_ALERTS: Set<Alert> = ENERGY_ALERTS + setOf(Alert.BatteryUnhealthy, Alert.BatteryUnknown)

        /** Every `GPS_FIX_TYPE` with a 3D position; anything else, an undefined type included, is no 3D fix. */
        val FIX_3D: Set<GpsFixType> = setOf(
            GpsFixType.Fix3d,
            GpsFixType.Dgps,
            GpsFixType.RtkFloat,
            GpsFixType.RtkFixed,
            GpsFixType.Static,
            GpsFixType.Ppp,
        )
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
