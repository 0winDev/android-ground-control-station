package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import kotlin.time.TimeMark

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

    /** The battery's remaining energy has crossed the critical threshold, whose [source] says whose it is. */
    data class BatteryCritical(val source: EnergyLimitSource) : Alert {
        override val level = AlertLevel.EMERGENCY
    }

    /** The battery's remaining energy has crossed the low threshold, but not the critical one. */
    data class BatteryLow(val source: EnergyLimitSource) : Alert {
        override val level = AlertLevel.CAUTION
    }

    /** The battery's voltage has stayed below the vehicle's critical threshold for its debounce ([VoltageLimit]). */
    data object BatteryVoltageCritical : Alert {
        override val level = AlertLevel.EMERGENCY
    }

    /** The battery's voltage has stayed below the vehicle's low threshold for its debounce, but not the critical. */
    data object BatteryVoltageLow : Alert {
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
     * A battery setting that sets a limit has stayed not read for a whole round of requests: the battery is judged at
     * the GCS's own energy thresholds and the voltage may not be judged at all.
     */
    data object BatteryLimitsNotRead : Alert {
        override val level = AlertLevel.ADVISORY
    }

    /**
     * The voltage alerts are off although a threshold is set: the vehicle sends its resting voltage while it compares
     * the raw one ([VoltageLimit.NotComparable]), so only the remaining energy can warn.
     */
    data object BatteryVoltageNotJudged : Alert {
        override val level = AlertLevel.ADVISORY
    }

    /**
     * The battery is unknown, never OK: the vehicle has no battery monitor, sends no voltage, sends no remaining energy
     * while no voltage threshold is known, or its battery reading has gone stale.
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
     * per kind, so acknowledging one stream that stopped never hides the next one that does. Once raised it lasts at
     * least [AlertRule.STALE_MINIMUM_HOLD], so a stream that is late for an instant does not flap.
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
 * a new occurrence, so acknowledging the one the operator saw never acknowledges the next. [raisedAt] is when it was
 * raised, and [origin] whether by the live link or a replay, so a recorded alert never passes for a live one.
 */
data class ActiveAlert(
    val alert: Alert,
    val occurrence: Long,
    val raisedAt: TimeMark,
    val origin: DataOrigin,
    val acknowledged: Boolean = false,
)

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

/**
 * What [AlertRule] keeps between evaluations: the [active] alerts, and since when the fresh voltage readings have
 * stayed on the other side of each voltage threshold from its alert, `null` while they have not ([VoltageLimit.Below]),
 * and since when a battery limit setting has not been read ([Alert.BatteryLimitsNotRead]).
 */
data class AlertState(
    val active: List<ActiveAlert> = emptyList(),
    val lowVoltageSince: TimeMark? = null,
    val criticalVoltageSince: TimeMark? = null,
    val limitsUnreadSince: TimeMark? = null,
)

/**
 * What [AlertRule] judges: the active vehicle's [telemetry], its [battery] failsafe settings as read, whether it is
 * [armed], and since when its alerts have been evaluated ([evaluatingSince]).
 */
data class AlertInput(
    val telemetry: Telemetry,
    val battery: BatteryThresholds,
    val armed: Boolean,
    val evaluatingSince: TimeMark,
)
