package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.telemetry.TelemetryKind

/**
 * How urgent an alert is, in the aviation convention: an advisory needs awareness, a caution needs attention
 * soon, an emergency needs it now. Declared from least to most urgent, so they compare by urgency. Losing the
 * vehicle's link outranks all of them (REQ-017).
 */
enum class AlertLevel {
    ADVISORY,
    CAUTION,
    EMERGENCY,
}

/** Something about the active vehicle the operator must know (REQ-028), raised and cleared by [AlertRule]. */
sealed interface Alert {

    val level: AlertLevel

    /** The battery's remaining energy is at or below the critical threshold. */
    data object BatteryCritical : Alert {
        override val level = AlertLevel.EMERGENCY
    }

    /** The battery's remaining energy is at or below the low threshold, but not critical. */
    data object BatteryLow : Alert {
        override val level = AlertLevel.CAUTION
    }

    /**
     * The vehicle reports its battery unhealthy. ArduPilot does so both when the monitor fails and once its own
     * battery failsafe has triggered, which then acts on the vehicle and lasts until it reboots or the battery is
     * reset with no pack still in failsafe (`libraries/GCS_MAVLink/GCS.cpp`, `reset_remaining_mask` in
     * `libraries/AP_BattMonitor/AP_BattMonitor.cpp`); the two look the same, so it is an emergency.
     */
    data object BatteryUnhealthy : Alert {
        override val level = AlertLevel.EMERGENCY
    }

    /**
     * The battery is unknown, never OK: the vehicle has no battery monitor, sends no voltage or remaining energy,
     * or its battery reading has gone stale.
     */
    data object BatteryUnknown : Alert {
        override val level = AlertLevel.ADVISORY
    }

    /** The GPS has no 3D fix, or nothing has been received from it yet. */
    data object GpsFixLost : Alert {
        override val level = AlertLevel.CAUTION
    }

    /**
     * The telemetry of [kind] is older than its staleness threshold, so what it shows may no longer be true. One
     * per kind, so acknowledging one stream that stopped never hides the next one that does.
     */
    data class TelemetryStale(val kind: TelemetryKind) : Alert {
        override val level = AlertLevel.ADVISORY
    }
}

/**
 * An [alert] that is active now, and whether the operator has [acknowledged] it. An acknowledged alert stays
 * active until its cause clears; acknowledging only says the operator has seen it.
 *
 * [occurrence] tells this raise apart from any other, before or after: an alert that clears and is raised again is
 * a new occurrence, so acknowledging the one the operator saw never acknowledges the next.
 */
data class ActiveAlert(val alert: Alert, val occurrence: Long, val acknowledged: Boolean = false)

/**
 * The active vehicle's alerts as far as they are known. [NotEvaluated] is never "all clear": like a link still
 * starting (#13), it says nothing about the vehicle, so it must never be shown as if nothing were wrong.
 */
sealed interface Alerts {

    /** Nothing has been judged: there is no active vehicle, or nobody has observed the alerts since. */
    data object NotEvaluated : Alerts

    /** Judged on the active vehicle's telemetry, most urgent first ([AlertRule]); empty is all clear. */
    data class Evaluated(val active: List<ActiveAlert>) : Alerts
}
