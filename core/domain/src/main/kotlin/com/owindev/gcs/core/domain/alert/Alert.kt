package com.owindev.gcs.core.domain.alert

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
     * battery failsafe has triggered, which then acts on the vehicle and lasts until it reboots
     * (`libraries/GCS_MAVLink/GCS.cpp`); the two look the same, so it is an emergency.
     */
    data object BatteryUnhealthy : Alert {
        override val level = AlertLevel.EMERGENCY
    }

    /** The vehicle has no battery monitor or sends no remaining energy: the battery is unknown, never OK. */
    data object BatteryUnknown : Alert {
        override val level = AlertLevel.ADVISORY
    }

    /** The GPS has no 3D fix. */
    data object GpsFixLost : Alert {
        override val level = AlertLevel.CAUTION
    }

    /** Some telemetry is older than its staleness threshold, so what it shows may no longer be true. */
    data object TelemetryStale : Alert {
        override val level = AlertLevel.ADVISORY
    }
}

/**
 * An [alert] that is active now, and whether the operator has [acknowledged] it. An acknowledged alert stays
 * active until its cause clears; acknowledging only says the operator has seen it.
 */
data class ActiveAlert(val alert: Alert, val acknowledged: Boolean = false)
