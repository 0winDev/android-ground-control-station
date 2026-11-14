package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.LowVoltageTimer
import com.owindev.gcs.core.domain.battery.SentVoltage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The thresholds the battery alerts judge (REQ-028), the vehicle's own wherever it has set them, so that the alerts and
 * the pre-flight check (#102) agree with the battery failsafe.
 */
data class BatteryAlertLimits(val low: BatteryLevelLimits, val critical: BatteryLevelLimits)

/** One level's thresholds: on the voltage and on the remaining energy. */
data class BatteryLevelLimits(val voltage: VoltageLimit, val energy: EnergyLimit)

/** A voltage threshold for an alert. */
sealed interface VoltageLimit {

    /** A setting it depends on has not been read, or the voltage sent may be above the one the vehicle compares. */
    data object Unknown : VoltageLimit

    /** No threshold: no monitor, a monitor that reads no voltage, or a threshold of 0 or less. */
    data object Off : VoltageLimit

    /**
     * Crossed by a voltage below [volts], as ArduPilot compares (`AP_BattMonitor_Backend::check_failsafe_types`), that
     * has stayed so for [debounce].
     */
    data class Below(val volts: Float, val debounce: Duration) : VoltageLimit
}

/** A remaining energy threshold for an alert, in percent, and whose it is. */
data class EnergyLimit(val percent: Double, val source: EnergyLimitSource)

enum class EnergyLimitSource {
    /** `BATT_LOW_MAH` or `BATT_CRT_MAH` over `BATT_CAPACITY`: crossed below it, as ArduPilot compares. */
    VEHICLE,

    /** The GCS's own, at or below which it is crossed: the vehicle's settings have not been read yet. */
    GCS_UNREAD,

    /** The GCS's own, at or below which it is crossed: the vehicle sets none (0 or less, or no usable capacity). */
    GCS_UNSET,
}

/**
 * The limits from the vehicle's [thresholds], with the GCS's [defaults] for any energy threshold it has not set.
 *
 * Both voltage levels share `BATT_LOW_TIMER` as their debounce, as `AP_BattMonitor_Backend::update_failsafes` does
 * (lines 162–163 critical, 179–180 low, ArduCopter 4.7.2); at 0 the vehicle never acts on voltage but the threshold
 * still warns, debounced by the parameter's default of 10 s (`AP_BattMonitor_Params.cpp`). The voltage the vehicle
 * sends must never be above the one it compares ([SentVoltage]), or a threshold it has crossed may not show.
 */
fun batteryAlertLimitsOf(thresholds: BatteryThresholds, defaults: GcsEnergyThresholds): BatteryAlertLimits =
    BatteryAlertLimits(
        low = BatteryLevelLimits(
            voltage = voltageLimitOf(thresholds, thresholds.low.voltage),
            energy = energyLimitOf(thresholds.low.remainingMah, thresholds.capacityMah, defaults.low),
        ),
        critical = BatteryLevelLimits(
            voltage = voltageLimitOf(thresholds, thresholds.critical.voltage),
            energy = energyLimitOf(thresholds.critical.remainingMah, thresholds.capacityMah, defaults.critical),
        ),
    )

private fun voltageLimitOf(thresholds: BatteryThresholds, threshold: BatteryThreshold): VoltageLimit {
    val monitor = thresholds.monitor
    return when {
        monitor == BatteryMonitor.Unknown -> VoltageLimit.Unknown

        monitor == BatteryMonitor.None -> VoltageLimit.Off

        monitor is BatteryMonitor.Present && !monitor.measuresVoltage -> VoltageLimit.Off

        threshold == BatteryThreshold.Unknown -> VoltageLimit.Unknown

        threshold !is BatteryThreshold.At -> VoltageLimit.Off

        thresholds.sentVoltage != SentVoltage.NEVER_ABOVE_COMPARED -> VoltageLimit.Unknown

        else -> when (val timer = thresholds.lowVoltageTimer) {
            LowVoltageTimer.Unknown -> VoltageLimit.Unknown
            LowVoltageTimer.Disabled -> VoltageLimit.Below(threshold.value, DEFAULT_LOW_TIMER)
            is LowVoltageTimer.Seconds -> VoltageLimit.Below(threshold.value, timer.value.seconds)
        }
    }
}

/**
 * ArduPilot sends no remaining energy with a `BATT_CAPACITY` of [MIN_CAPACITY_MAH] or less
 * (`AP_BattMonitor_Backend::capacity_remaining_pct`), so a capacity threshold then sets no percentage.
 */
private fun energyLimitOf(remainingMah: BatteryThreshold, capacityMah: Int?, default: Int): EnergyLimit = when {
    remainingMah == BatteryThreshold.Disabled -> EnergyLimit(default.toDouble(), EnergyLimitSource.GCS_UNSET)

    remainingMah == BatteryThreshold.Unknown || capacityMah == null ->
        EnergyLimit(default.toDouble(), EnergyLimitSource.GCS_UNREAD)

    remainingMah is BatteryThreshold.At && capacityMah > MIN_CAPACITY_MAH ->
        EnergyLimit(PERCENT * remainingMah.value / capacityMah, EnergyLimitSource.VEHICLE)

    else -> EnergyLimit(default.toDouble(), EnergyLimitSource.GCS_UNSET)
}

/** `BATT_LOW_TIMER`'s default (`AP_BattMonitor_Params.cpp`). */
private val DEFAULT_LOW_TIMER: Duration = 10.seconds
private const val MIN_CAPACITY_MAH = 10
private const val PERCENT = 100.0
