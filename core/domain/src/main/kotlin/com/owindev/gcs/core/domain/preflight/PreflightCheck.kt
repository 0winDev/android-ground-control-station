package com.owindev.gcs.core.domain.preflight

import com.owindev.gcs.core.domain.battery.FailsafeStatus
import kotlin.time.TimeMark

/** How a pre-flight item stands (REQ-048): red blocks arm and take off, amber must be acknowledged before arming. */
enum class PreflightColour {
    GREEN,
    AMBER,
    RED,
}

/** What a pre-flight item checks; one item of each kind, in this order. */
enum class PreflightKind {
    LINK,
    UPLINK,
    BACKGROUND_LINK,
    VEHICLE_PREARM,
    GPS,
    BATTERY,
    BATTERY_LIMITS,
    HOME,
    POSITION,
    ATTITUDE,
    ALERTS,
}

/** One pre-flight item. An amber one's [reason] holds no live value, so its acknowledgement survives new readings. */
data class PreflightItem(val kind: PreflightKind, val colour: PreflightColour, val reason: PreflightReason)

/**
 * The pre-flight items of the active vehicle at one moment, one per [PreflightKind] ([PreflightRule]), and the
 * [connection] they were judged on ([PreflightInput.connection]).
 */
data class PreflightCheck(val items: List<PreflightItem>, val connection: Long) {

    val reds: List<PreflightItem> get() = items.filter { it.colour == PreflightColour.RED }

    val ambers: List<PreflightItem> get() = items.filter { it.colour == PreflightColour.AMBER }
}

/** Why a pre-flight item has its colour; a stale value and one never received are different reasons (#73). */
sealed interface PreflightReason {

    data object Ok : PreflightReason

    data object NotReceived : PreflightReason

    data object Stale : PreflightReason

    data object Replay : PreflightReason

    data object NoActiveVehicle : PreflightReason

    data object LinkLost : PreflightReason

    data object UplinkSendsFailing : PreflightReason

    data object UplinkCommandUnanswered : PreflightReason

    /** No COMMAND_ACK to this GCS since the vehicle became CONNECTED: no sign yet that it hears this GCS. */
    data object NotAnsweredYet : PreflightReason

    data object BackgroundLinkNotRunning : PreflightReason

    data object BackgroundRestricted : PreflightReason

    /** SYS_STATUS does not report the vehicle's pre-arm checks at all. */
    data object PreArmStateUnknown : PreflightReason

    /** The vehicle has no arming check enabled. */
    data object ArmingChecksDisabled : PreflightReason

    /**
     * The vehicle's pre-arm checks fail. [text] is its latest `PreArm:` reason and [receivedAt] when it came, both `null`
     * when none has been received yet; [current] is `false` when that reason is older than
     * [com.owindev.gcs.core.domain.message.PreArmRule.maxAge].
     */
    data class PreArmFailing(val text: String?, val current: Boolean, val receivedAt: TimeMark? = null) :
        PreflightReason

    data object NoFix3d : PreflightReason

    data object BatteryUnhealthy : PreflightReason

    data object BatteryCritical : PreflightReason

    data object BatteryLow : PreflightReason

    /** The battery cannot be judged: the vehicle has no monitor, or sends nothing a limit can be compared with. */
    data class BatteryUnknown(val cause: BatteryUnknownCause) : PreflightReason

    data object LimitsNotRead : PreflightReason

    /** A battery failsafe level would not act: each level's status as read. */
    data class FailsafeNotArmed(val low: FailsafeStatus, val critical: FailsafeStatus) : PreflightReason

    data object HomeUnknown : PreflightReason

    data object EmergencyAlert : PreflightReason

    /** Nobody observes the alerts, so none is held; the battery and GPS items judge the same causes on their own. */
    data object AlertsNotEvaluated : PreflightReason
}

enum class BatteryUnknownCause {
    NO_MONITOR,
    NO_READING,
}
