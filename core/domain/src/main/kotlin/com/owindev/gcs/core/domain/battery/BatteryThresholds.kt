package com.owindev.gcs.core.domain.battery

/**
 * The failsafe settings of the vehicle's first battery (`BATT_*`, REQ-028), as read from it. Anything not read yet is
 * unknown, and an unknown setting never reads as disabled or as no monitor: [statusOf] is then
 * [FailsafeStatus.UNKNOWN].
 */
data class BatteryThresholds(
    val monitor: BatteryMonitor,
    val low: BatteryFailsafeLevel,
    val critical: BatteryFailsafeLevel,
    /** `BATT_CAPACITY`, `null` until read. */
    val capacityMah: Int?,
) {

    /** Whether [level] (this battery's [low] or [critical]) would act, and how. */
    fun statusOf(level: BatteryFailsafeLevel): FailsafeStatus {
        val thresholds = listOf(level.voltage, level.remainingMah)
        return when {
            monitor == BatteryMonitor.Unknown -> FailsafeStatus.UNKNOWN
            monitor == BatteryMonitor.None -> FailsafeStatus.NO_MONITOR
            BatteryThreshold.Unknown in thresholds -> FailsafeStatus.UNKNOWN
            thresholds.all { it == BatteryThreshold.Disabled } -> FailsafeStatus.NO_THRESHOLD
            level.action == FailsafeAction.Unknown -> FailsafeStatus.UNKNOWN
            level.action == FailsafeAction.WarnOnly -> FailsafeStatus.WARN_ONLY
            else -> FailsafeStatus.ARMED
        }
    }

    companion object {
        /** Nothing read: the state with no active vehicle, and after a reboot or a session change. */
        val UNKNOWN = BatteryThresholds(
            monitor = BatteryMonitor.Unknown,
            low = BatteryFailsafeLevel.UNKNOWN,
            critical = BatteryFailsafeLevel.UNKNOWN,
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
    /** A setting it depends on has not been read. */
    UNKNOWN,

    /** `BATT_MONITOR` 0: the vehicle measures no battery, so no threshold applies. */
    NO_MONITOR,

    /** Both thresholds disabled. */
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

    /** 0, "Disabled": ArduPilot then hides the other `BATT_*` parameters. */
    data object None : BatteryMonitor

    /** Any other value: the monitor's type, kept as sent. */
    data class Present(val type: Int) : BatteryMonitor

    companion object {
        fun of(type: Int?): BatteryMonitor = when (type) {
            null -> Unknown
            MONITOR_DISABLED -> None
            else -> Present(type)
        }

        private const val MONITOR_DISABLED = 0
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

    /** Any other value, kept as sent: its meaning depends on the vehicle type (`@Values{Copter}` 1 Land … 7). */
    data class Acts(val code: Int) : FailsafeAction

    companion object {
        fun of(code: Int?): FailsafeAction = when (code) {
            null -> Unknown
            ACTION_NONE -> WarnOnly
            else -> Acts(code)
        }

        private const val ACTION_NONE = 0
    }
}
