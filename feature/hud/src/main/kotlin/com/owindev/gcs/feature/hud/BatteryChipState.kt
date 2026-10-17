package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.telemetry.Battery

/**
 * What the battery chip says (REQ-029), decided apart from Compose so it can be tested. The chip never shows an
 * unknown or unhealthy battery as an ordinary reading (#41), even while the alert banner shows another alert.
 */
internal sealed interface BatteryChipState {

    /** Nothing received from the battery yet. */
    data object NoReading : BatteryChipState

    /** The vehicle has no battery monitor: the battery is unknown, never OK. */
    data object NoMonitor : BatteryChipState

    /**
     * What the monitor reads, each value `null` when not sent. Not [healthy] is ArduPilot's battery failsafe or a
     * failed monitor, which still sends its readings (#41): shown, but marked unhealthy in words and colour.
     */
    data class Measured(val remaining: Int?, val voltage: Double?, val healthy: Boolean) : BatteryChipState
}

internal fun batteryChipStateOf(battery: Battery?): BatteryChipState = when {
    battery == null -> BatteryChipState.NoReading
    !battery.monitored -> BatteryChipState.NoMonitor
    else -> BatteryChipState.Measured(battery.remaining, battery.voltage, battery.healthy)
}
