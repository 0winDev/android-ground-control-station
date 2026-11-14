package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.telemetry.Battery
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How [AlertRule] judges a fresh battery reading (REQ-028), at the limits [batteryAlertLimitsOf] gives with the GCS's
 * [thresholds].
 *
 * A battery monitor that is not healthy is an emergency ([Alert.BatteryUnhealthy]), except while the vehicle is
 * disarmed and the monitor has no voltage reading: ArduPilot can send that at boot, before the monitor has read
 * anything (`truncated/sys_status.bin`, enabled, not healthy, 0 mV), and it is then only unknown
 * ([Alert.BatteryUnknown]). Armed, a monitor that fails is always an emergency, reading or not, and it stays one
 * after disarming: only a monitor that was not already an emergency is played down, so the rule covers the boot
 * and never a failure seen in flight. On ArduPilot's battery failsafe the voltage keeps arriving (#41), so it
 * stays an emergency armed or not, and the voltage is still judged.
 *
 * A voltage alert is raised once the fresh readings have stayed below its threshold for the threshold's debounce,
 * and cleared once they have stayed at or above it for as long ([VoltageLimit.Below]); only consecutive readings
 * count, so with no reading, or a threshold not known, each debounce starts again. A voltage of 0 is no reading:
 * ArduPilot compares only a voltage above 0 (`check_failsafe_types`).
 */
internal class BatteryAlertRule(
    private val thresholds: GcsEnergyThresholds,
    private val timeSource: TimeSource.WithComparableMarks,
) {

    /** The alerts for a fresh [battery] reading, from the battery alerts [wasActive] and the [previous] debounces. */
    fun alertsOf(battery: Battery, input: AlertInput, previous: AlertState, wasActive: Set<Alert>): BatteryJudgement {
        val limits = limitsOf(input.battery)
        val voltage = battery.voltage?.takeIf { battery.monitored && it > 0.0 }
        val remaining = battery.remaining
        val voltageJudged = limits.anyVoltage { it is VoltageLimit.Below }
        val judged = voltageAlerts(voltage, limits, previous, wasActive)
        val alerts = buildSet {
            // A reading needs a monitor, so no voltage covers no monitor too.
            if (voltage == null || !(voltageJudged || remaining != null)) add(Alert.BatteryUnknown)
            // Disarmed with no reading yet, as at boot, an unhealthy monitor is only unknown (added above), unless it
            // was already an emergency: a failure seen in flight is not played down by landing.
            val failing = battery.monitored && !battery.healthy
            val playedDown = !input.armed && voltage == null && Alert.BatteryUnhealthy !in wasActive
            if (failing && !playedDown) add(Alert.BatteryUnhealthy)
            // Without a monitor or a remaining energy, the energy alerts stay as they were. An unhealthy monitor still
            // drives them: ArduPilot sends no remaining energy (-1) when the monitor itself fails, so a percentage from
            // an unhealthy one is real, sent after the battery failsafe has triggered and while the battery goes on
            // draining (`battery_remaining_pct` in `libraries/GCS_MAVLink/GCS_Common.cpp`, which needs a healthy
            // monitor in `capacity_remaining_pct`, `libraries/AP_BattMonitor/AP_BattMonitor_Backend.cpp`).
            if (!battery.monitored || remaining == null) {
                addAll(wasActive.filter(::isEnergyAlert))
            } else {
                addAll(energyAlerts(remaining, limits, wasActive))
            }
            judged.alert?.let(::add)
            if (limits.anyVoltage { it == VoltageLimit.NotComparable }) add(Alert.BatteryVoltageNotJudged)
        }
        return BatteryJudgement(alerts, judged.low.since, judged.critical.since)
    }

    /**
     * Whether [Alert.BatteryLimitsNotRead] is raised: once a setting that sets a limit has stayed not read for
     * [AlertRule.BATTERY_LIMITS_DUE] since [since], or since now if it was read until now; and the new mark, `null`
     * once all are read.
     */
    fun limitsNotRead(battery: BatteryThresholds, since: TimeMark?): Held {
        if (!battery.anyLimitUnread()) return Held(raised = false, since = null)
        val unreadSince = since ?: timeSource.markNow()
        return Held(raised = unreadSince.elapsedNow() >= AlertRule.BATTERY_LIMITS_DUE, since = unreadSince)
    }

    /** How long until each voltage debounce running in [state] ends, at the vehicle's [battery] thresholds. */
    fun untilDebounceEnds(state: AlertState, battery: BatteryThresholds): List<Duration> {
        val limits = limitsOf(battery)
        return listOfNotNull(
            untilDebounceEnds(limits.low.voltage, state.lowVoltageSince),
            untilDebounceEnds(limits.critical.voltage, state.criticalVoltageSince),
        )
    }

    private fun limitsOf(battery: BatteryThresholds): BatteryAlertLimits = batteryAlertLimitsOf(battery, thresholds)

    private fun untilDebounceEnds(limit: VoltageLimit, since: TimeMark?): Duration? =
        if (limit is VoltageLimit.Below && since != null) limit.debounce - since.elapsedNow() else null

    /**
     * Crossed as its source compares: below the vehicle's threshold (`check_failsafe_types`; the percentage sent is
     * truncated, so this is never later than the vehicle), at or below the GCS's own. Cleared only once the energy is
     * [GcsEnergyThresholds.hysteresis] above it. A critical energy is low as well, so it is low until it clears.
     */
    private fun energyAlert(remaining: Int, limits: BatteryAlertLimits, wasActive: Set<Alert>): Alert? {
        val critical = limits.critical.energy
        val low = limits.low.energy
        val wasCritical = wasActive.any { it is Alert.BatteryCritical }
        val wasLow = wasCritical || wasActive.any { it is Alert.BatteryLow }
        return when {
            crosses(remaining, critical) -> Alert.BatteryCritical(critical.source)

            wasCritical && remaining < critical.percent + thresholds.hysteresis ->
                Alert.BatteryCritical(critical.source)

            crosses(remaining, low) -> Alert.BatteryLow(low.source)

            wasLow && remaining < low.percent + thresholds.hysteresis -> Alert.BatteryLow(low.source)

            else -> null
        }
    }

    /**
     * The energy alert at [limits]. While the vehicle's limits are not known again, as after a reboot, with an alert
     * raised at them active, that alert is kept, since a value not known never clears one, and the energy is judged at
     * the GCS's as well, so it can still get worse: the more urgent of the two stays, the one active if both are as
     * urgent. Once it has got worse no alert at the vehicle's is held, so an energy that rises again clears at the
     * GCS's thresholds; accepted, since a reboot resets the energy used and [Alert.BatteryLimitsNotRead] is up by then.
     */
    private fun energyAlerts(remaining: Int, limits: BatteryAlertLimits, wasActive: Set<Alert>): Set<Alert> {
        val judged = setOfNotNull(energyAlert(remaining, limits, wasActive))
        if (!unreadAfterVehicle(limits, wasActive)) return judged
        val candidates = wasActive.filter(::isEnergyAlert) + judged
        val mostUrgent = candidates.filter { it.level == candidates.maxOf(Alert::level) }
        return setOf(mostUrgent.firstOrNull { it in wasActive } ?: mostUrgent.first())
    }

    /** The critical voltage alert if raised, else the low one: a critical voltage is low as well, as for the energy. */
    private fun voltageAlerts(
        voltage: Double?,
        limits: BatteryAlertLimits,
        previous: AlertState,
        wasActive: Set<Alert>,
    ): VoltageJudgement {
        val wasCritical = Alert.BatteryVoltageCritical in wasActive
        val wasLow = wasCritical || Alert.BatteryVoltageLow in wasActive
        val critical = voltageLevel(voltage, limits.critical.voltage, wasCritical, previous.criticalVoltageSince)
        val low = voltageLevel(voltage, limits.low.voltage, wasLow, previous.lowVoltageSince)
        val alert = when {
            critical.raised -> Alert.BatteryVoltageCritical
            low.raised -> Alert.BatteryVoltageLow
            else -> null
        }
        return VoltageJudgement(alert, low, critical)
    }

    /**
     * Whether a voltage level's alert is raised now, and since when the readings have been on the other side of its
     * threshold. A threshold that is off clears it; one not known or not comparable, or no [voltage] reading, keeps it
     * as it was and starts its debounce again. A voltage compares as the float ArduPilot compares
     * (`check_failsafe_types`).
     *
     * A critical level keeps the low one raised, so with a critical threshold at or above the low one, which
     * ArduPilot refuses to arm with (`fs_voltage_inversion`), clearing the critical alert shows the low one for one
     * more debounce.
     */
    private fun voltageLevel(voltage: Double?, limit: VoltageLimit, wasRaised: Boolean, since: TimeMark?): Held = when {
        limit == VoltageLimit.Off -> Held(raised = false, since = null)

        limit !is VoltageLimit.Below || voltage == null -> Held(raised = wasRaised, since = null)

        (voltage.toFloat() < limit.volts) == wasRaised -> Held(raised = wasRaised, since = null)

        else -> {
            val crossingSince = since ?: timeSource.markNow()
            if (crossingSince.elapsedNow() >= limit.debounce) {
                Held(raised = !wasRaised, since = null)
            } else {
                Held(raised = wasRaised, since = crossingSince)
            }
        }
    }

    private data class VoltageJudgement(val alert: Alert?, val low: Held, val critical: Held)

    companion object {
        fun isBatteryAlert(alert: Alert): Boolean = isEnergyAlert(alert) || alert in OTHER_BATTERY_ALERTS

        private fun isEnergyAlert(alert: Alert): Boolean = alert is Alert.BatteryCritical || alert is Alert.BatteryLow

        private val OTHER_BATTERY_ALERTS: Set<Alert> = setOf(
            Alert.BatteryVoltageCritical,
            Alert.BatteryVoltageLow,
            Alert.BatteryVoltageNotJudged,
            Alert.BatteryUnhealthy,
            Alert.BatteryUnknown,
        )
    }
}

/** A level's energy limit is not known again while an alert raised at the vehicle's is active. */
private fun unreadAfterVehicle(limits: BatteryAlertLimits, wasActive: Set<Alert>): Boolean {
    val unread = limits.low.energy.source == EnergyLimitSource.GCS_UNREAD ||
        limits.critical.energy.source == EnergyLimitSource.GCS_UNREAD
    return unread && wasActive.any { energySourceOf(it) == EnergyLimitSource.VEHICLE }
}

private fun crosses(remaining: Int, limit: EnergyLimit): Boolean =
    if (limit.source == EnergyLimitSource.VEHICLE) remaining < limit.percent else remaining <= limit.percent

private fun energySourceOf(alert: Alert): EnergyLimitSource? = when (alert) {
    is Alert.BatteryCritical -> alert.source
    is Alert.BatteryLow -> alert.source
    else -> null
}

private fun BatteryAlertLimits.anyVoltage(predicate: (VoltageLimit) -> Boolean): Boolean =
    predicate(low.voltage) || predicate(critical.voltage)

/** Whether an alert is raised, and since when what would change it has lasted, `null` while nothing does. */
internal data class Held(val raised: Boolean, val since: TimeMark?)

/** The battery's alerts, and since when each voltage level's readings have been across its threshold. */
internal data class BatteryJudgement(
    val alerts: Set<Alert>,
    val lowVoltageSince: TimeMark? = null,
    val criticalVoltageSince: TimeMark? = null,
)
