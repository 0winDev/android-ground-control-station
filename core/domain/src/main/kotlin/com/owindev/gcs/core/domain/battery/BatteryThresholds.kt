package com.owindev.gcs.core.domain.battery

/**
 * The failsafe settings of the vehicle's first battery (`BATT_*`, REQ-028), as read from it. Anything not read yet is
 * unknown, and an unknown setting never reads as disabled or as no monitor: [lowStatus] and [criticalStatus] are then
 * [FailsafeStatus.UNKNOWN].
 */
data class BatteryThresholds(
    val monitor: BatteryMonitor,
    val low: BatteryFailsafeLevel,
    val critical: BatteryFailsafeLevel,
    /** `BATT_LOW_TIMER`, shared by both voltage thresholds. */
    val lowVoltageTimer: LowVoltageTimer,
    /** `BATT_CAPACITY`, `null` until read. */
    val capacityMah: Int?,
) {

    /** Whether the [low] level would act, and how (see [statusOf]). */
    val lowStatus: FailsafeStatus get() = statusOf(level = low, other = critical)

    /** Whether the [critical] level would act, and how (see [statusOf]). */
    val criticalStatus: FailsafeStatus get() = statusOf(level = critical, other = low)

    /**
     * A threshold counts only if ArduPilot would ever cross it (`AP_BattMonitor_Backend::update_failsafes` and
     * `check_failsafe_types`): a voltage one needs [lowVoltageTimer] above 0 and a monitor that
     * [BatteryMonitor.Present.measuresVoltage], a capacity one a monitor that [BatteryMonitor.Present.measuresCurrent].
     * An action that [FailsafeAction.Acts.mayBeSkipped] is armed only while the [other] level can never fire first.
     */
    private fun statusOf(level: BatteryFailsafeLevel, other: BatteryFailsafeLevel): FailsafeStatus {
        val triggers = triggersOf(level)
        return when {
            monitor == BatteryMonitor.Unknown -> FailsafeStatus.UNKNOWN
            monitor == BatteryMonitor.None -> FailsafeStatus.NO_MONITOR
            Trigger.UNKNOWN in triggers -> FailsafeStatus.UNKNOWN
            triggers.all { it == Trigger.OFF } -> FailsafeStatus.NO_THRESHOLD
            level.action == FailsafeAction.Unknown -> FailsafeStatus.UNKNOWN
            level.action == FailsafeAction.WarnOnly -> FailsafeStatus.WARN_ONLY
            level.action.mayBeSkipped() && triggersOf(other).any { it != Trigger.OFF } -> FailsafeStatus.UNKNOWN
            else -> FailsafeStatus.ARMED
        }
    }

    private fun triggersOf(level: BatteryFailsafeLevel) =
        listOf(voltageTrigger(level.voltage), capacityTrigger(level.remainingMah))

    private fun FailsafeAction.mayBeSkipped(): Boolean = (this as? FailsafeAction.Acts)?.mayBeSkipped == true

    private fun voltageTrigger(threshold: BatteryThreshold): Trigger = when (threshold) {
        BatteryThreshold.Unknown -> Trigger.UNKNOWN
        BatteryThreshold.Disabled -> Trigger.OFF
        is BatteryThreshold.At -> if (measuresVoltage()) timerTrigger() else Trigger.OFF
    }

    private fun timerTrigger(): Trigger = when (lowVoltageTimer) {
        LowVoltageTimer.Unknown -> Trigger.UNKNOWN
        LowVoltageTimer.Disabled -> Trigger.OFF
        is LowVoltageTimer.Seconds -> Trigger.ON
    }

    private fun capacityTrigger(threshold: BatteryThreshold): Trigger = when (threshold) {
        BatteryThreshold.Unknown -> Trigger.UNKNOWN
        BatteryThreshold.Disabled -> Trigger.OFF
        is BatteryThreshold.At -> if (measuresCurrent()) Trigger.ON else Trigger.UNKNOWN
    }

    private fun measuresVoltage(): Boolean = (monitor as? BatteryMonitor.Present)?.measuresVoltage != false

    private fun measuresCurrent(): Boolean = (monitor as? BatteryMonitor.Present)?.measuresCurrent == true

    private enum class Trigger { ON, OFF, UNKNOWN }

    companion object {
        /** Nothing read: the state with no active vehicle, and after a reboot or a session change. */
        val UNKNOWN = BatteryThresholds(
            monitor = BatteryMonitor.Unknown,
            low = BatteryFailsafeLevel.UNKNOWN,
            critical = BatteryFailsafeLevel.UNKNOWN,
            lowVoltageTimer = LowVoltageTimer.Unknown,
            capacityMah = null,
        )
    }
}

/** One failsafe level (low or critical): its two thresholds and what the vehicle does when either is crossed. */
data class BatteryFailsafeLevel(
    /** `BATT_LOW_VOLT` or `BATT_CRT_VOLT`, in volts. */
    val voltage: BatteryThreshold,
    /** `BATT_LOW_MAH` or `BATT_CRT_MAH`: capacity left, in mAh. */
    val remainingMah: BatteryThreshold,
    /** `BATT_FS_LOW_ACT` or `BATT_FS_CRT_ACT`. */
    val action: FailsafeAction,
) {
    companion object {
        val UNKNOWN = BatteryFailsafeLevel(BatteryThreshold.Unknown, BatteryThreshold.Unknown, FailsafeAction.Unknown)
    }
}

/** What a failsafe level would do, from what is known of it. */
enum class FailsafeStatus {
    /** A setting it depends on has not been read, or the monitor's type is not known to measure what it compares. */
    UNKNOWN,

    /** `BATT_MONITOR` 0: the vehicle measures no battery, so no threshold applies. */
    NO_MONITOR,

    /** Neither threshold can be crossed: both disabled, or a voltage one with `BATT_LOW_TIMER` 0 or no voltage read. */
    NO_THRESHOLD,

    /** A threshold is set but its action is 0: the vehicle only warns, in flight. */
    WARN_ONLY,

    /** A threshold is set and crossing it makes the vehicle act. */
    ARMED,
}

/** A battery failsafe threshold. */
sealed interface BatteryThreshold {
    data object Unknown : BatteryThreshold

    /** 0 or less: ArduPilot only compares a threshold above 0 (`AP_BattMonitor_Backend::update_failsafes`). */
    data object Disabled : BatteryThreshold

    data class At(val value: Float) : BatteryThreshold

    companion object {
        /** [value] as read from the vehicle, `null` until then; a value that is not finite is no reading. */
        fun of(value: Float?): BatteryThreshold = when {
            value == null || !value.isFinite() -> Unknown
            value > 0f -> At(value)
            else -> Disabled
        }
    }
}

/** `BATT_MONITOR`: whether the vehicle measures this battery at all. */
sealed interface BatteryMonitor {
    data object Unknown : BatteryMonitor

    /** 0, "Disabled": ArduPilot then drops the other `BATT_*` parameters from its list and measures nothing. */
    data object None : BatteryMonitor

    /** Any other value: the monitor's type, kept as sent. */
    data class Present(val type: Int) : BatteryMonitor {
        /** Whether this type is known to measure current, so that a capacity threshold can be crossed. */
        val measuresCurrent: Boolean get() = type in CURRENT_MEASURING_TYPES

        /** Whether this type reads a voltage: 31, analog current only, reads 0 V (`AP_BattMonitor_Analog::read`). */
        val measuresVoltage: Boolean get() = type != ANALOG_CURRENT_ONLY
    }

    companion object {
        fun of(type: Int?): BatteryMonitor = when (type) {
            null -> Unknown
            MONITOR_DISABLED -> None
            else -> Present(type)
        }

        private const val MONITOR_DISABLED = 0
        private const val ANALOG_CURRENT_ONLY = 31

        /**
         * The `AP_BattMonitor_Params::Type`s whose backend's `has_current()` holds once running (ArduCopter
         * 4.7.3-beta1): 4 analog voltage and current and 31 analog current only (`AP_BattMonitor_Analog.cpp`),
         * 7 SMBus generic (`AP_BattMonitor_SMBus.h`), 8 DroneCAN battery info (`AP_BattMonitor_DroneCAN.h`), 21 INA2XX
         * (`AP_BattMonitor_INA2xx.h`). Any other type is not judged.
         */
        private val CURRENT_MEASURING_TYPES = setOf(4, 7, 8, 21, 31)
    }
}

/** `BATT_LOW_TIMER`: how long a voltage must stay below a threshold before the failsafe acts. */
sealed interface LowVoltageTimer {
    data object Unknown : LowVoltageTimer

    /** 0 or less: "A value of zero disables low voltage errors" (`AP_BattMonitor_Params.cpp`). */
    data object Disabled : LowVoltageTimer

    data class Seconds(val value: Int) : LowVoltageTimer

    companion object {
        fun of(seconds: Int?): LowVoltageTimer = when {
            seconds == null -> Unknown
            seconds > 0 -> Seconds(seconds)
            else -> Disabled
        }
    }
}

/** `BATT_FS_LOW_ACT` / `BATT_FS_CRT_ACT`. */
sealed interface FailsafeAction {
    data object Unknown : FailsafeAction

    /**
     * 0, ArduCopter's `FailsafeAction::NONE` ("Warn only"). Only in flight: `Copter::handle_battery_failsafe` disarms a
     * landed vehicle whatever its action.
     */
    data object WarnOnly : FailsafeAction

    /** One of ArduCopter's actions, `@Values{Copter}` 1 Land … 7 Brake or Land (`Copter::FailsafeAction`). */
    data class Acts(val code: Int) : FailsafeAction {
        /**
         * Whether ArduCopter can skip this action: `Copter::_failsafe_priorities` (`Copter.h`) leaves out 6 and 7, so
         * they rank below every other action and below NONE, and `AP_BattMonitor::check_failsafes` runs an action only
         * if it ranks at least as high as the last battery failsafe's, until a reboot or `reset_remaining_mask`.
         */
        val mayBeSkipped: Boolean get() = code in UNRANKED_ACTIONS
    }

    companion object {
        /** Any other code is [Unknown]: `Copter::do_failsafe_action` has no case for it, so it does nothing. */
        fun of(code: Int?): FailsafeAction = when (code) {
            null -> Unknown
            ACTION_NONE -> WarnOnly
            in COPTER_ACTIONS -> Acts(code)
            else -> Unknown
        }

        private const val ACTION_NONE = 0
        private val COPTER_ACTIONS = 1..7
        private val UNRANKED_ACTIONS = setOf(6, 7)
    }
}
