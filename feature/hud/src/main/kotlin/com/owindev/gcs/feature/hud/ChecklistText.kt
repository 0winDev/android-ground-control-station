package com.owindev.gcs.feature.hud

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.preflight.BatteryUnknownCause
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import kotlin.time.Duration

// How the pre-flight checklist says each item (REQ-048): a title per kind, a reason in words, a colour with its icon
// and word.

/** A text from resources: [res] with [args], each a value, a [Duration] written as an age, or nested [Words]. */
internal data class Words(@param:StringRes val res: Int, val args: List<Any> = emptyList())

/** This item's reason in words; `null` for an item that is fine, whose word already says so. */
// One branch per reason, so a new reason does not compile without its words: a warning must say its true reason (#73).
@Suppress("CyclomaticComplexMethod")
internal fun ChecklistItem.reasonWords(): Words? = when (val reason = item.reason) {
    PreflightReason.Ok -> null

    PreflightReason.NotReceived -> Words(R.string.hud_reason_not_received)

    PreflightReason.Stale -> Words(R.string.hud_reason_stale)

    PreflightReason.Replay -> Words(R.string.hud_reason_replay)

    PreflightReason.NoActiveVehicle -> Words(R.string.hud_reason_no_active_vehicle)

    PreflightReason.LinkLost -> Words(R.string.hud_reason_link_lost)

    PreflightReason.UplinkSendsFailing -> Words(R.string.hud_reason_uplink_sends_failing)

    PreflightReason.UplinkCommandUnanswered -> Words(R.string.hud_reason_uplink_command_unanswered)

    PreflightReason.NotAnsweredYet -> Words(R.string.hud_reason_not_answered_yet)

    PreflightReason.BackgroundLinkNotRunning -> Words(R.string.hud_reason_background_not_running)

    PreflightReason.BackgroundRestricted -> Words(R.string.hud_reason_background_restricted)

    PreflightReason.PreArmStateUnknown -> Words(R.string.hud_reason_prearm_unknown)

    PreflightReason.ArmingChecksDisabled -> Words(R.string.hud_reason_arming_checks_disabled)

    is PreflightReason.PreArmFailing -> preArmWords(reason, reasonAge)

    PreflightReason.NoFix3d -> Words(R.string.hud_reason_no_fix_3d)

    PreflightReason.BatteryUnhealthy -> Words(R.string.hud_reason_battery_unhealthy)

    PreflightReason.BatteryCritical -> Words(R.string.hud_reason_battery_critical)

    PreflightReason.BatteryLow -> Words(R.string.hud_reason_battery_low)

    is PreflightReason.BatteryUnknown -> Words(
        when (reason.cause) {
            BatteryUnknownCause.NO_MONITOR -> R.string.hud_reason_battery_no_monitor
            BatteryUnknownCause.NO_READING -> R.string.hud_reason_battery_no_reading
        },
    )

    PreflightReason.LimitsNotRead -> Words(R.string.hud_reason_limits_not_read)

    is PreflightReason.FailsafeNotArmed -> Words(
        R.string.hud_reason_failsafe_not_armed,
        listOf(Words(reason.low.words()), Words(reason.critical.words())),
    )

    PreflightReason.HomeUnknown -> Words(R.string.hud_reason_home_unknown)

    PreflightReason.EmergencyAlert -> Words(R.string.hud_reason_emergency_alert)

    PreflightReason.AlertsNotEvaluated -> Words(R.string.hud_reason_alerts_not_evaluated)
}

private fun preArmWords(reason: PreflightReason.PreArmFailing, age: Duration?): Words {
    val text = reason.text
    return when {
        text == null || age == null -> Words(R.string.hud_reason_prearm_failing_no_text)
        reason.current -> Words(R.string.hud_reason_prearm_failing, listOf(text, age))
        else -> Words(R.string.hud_reason_prearm_failing_old, listOf(text, age))
    }
}

// A handful of arguments, once per row on a sheet: the array copy costs nothing worth avoiding.
@Suppress("SpreadOperator")
@Composable
internal fun Words.text(): String = stringResource(
    res,
    *args.map { arg ->
        when (arg) {
            is Words -> arg.text()
            is Duration -> elapsedText(arg)
            else -> arg
        }
    }.toTypedArray(),
)

@StringRes
private fun FailsafeStatus.words(): Int = when (this) {
    FailsafeStatus.UNKNOWN -> R.string.hud_failsafe_unknown
    FailsafeStatus.NO_MONITOR -> R.string.hud_failsafe_no_monitor
    FailsafeStatus.NO_THRESHOLD -> R.string.hud_failsafe_no_threshold
    FailsafeStatus.WARN_ONLY -> R.string.hud_failsafe_warn_only
    FailsafeStatus.ARMED -> R.string.hud_failsafe_armed
}

@StringRes
internal fun PreflightKind.title(): Int = when (this) {
    PreflightKind.LINK -> R.string.hud_checklist_link
    PreflightKind.UPLINK -> R.string.hud_checklist_uplink
    PreflightKind.BACKGROUND_LINK -> R.string.hud_checklist_background_link
    PreflightKind.VEHICLE_PREARM -> R.string.hud_checklist_vehicle_prearm
    PreflightKind.GPS -> R.string.hud_checklist_gps
    PreflightKind.BATTERY -> R.string.hud_checklist_battery
    PreflightKind.BATTERY_LIMITS -> R.string.hud_checklist_battery_limits
    PreflightKind.HOME -> R.string.hud_checklist_home
    PreflightKind.POSITION -> R.string.hud_checklist_position
    PreflightKind.ATTITUDE -> R.string.hud_checklist_attitude
    PreflightKind.ALERTS -> R.string.hud_checklist_alerts
}

/** Colour, icon and word together: colour is never the only signal. */
internal class ItemLook(val color: Color, @param:DrawableRes val icon: Int, @param:StringRes val word: Int)

@Composable
internal fun PreflightColour.look(): ItemLook = when (this) {
    PreflightColour.GREEN ->
        ItemLook(GcsTheme.colors.nominal, DesignSystemR.drawable.ic_check_circle, R.string.hud_checklist_ok)

    PreflightColour.AMBER ->
        ItemLook(GcsTheme.colors.caution, DesignSystemR.drawable.ic_warning, R.string.hud_checklist_caution)

    PreflightColour.RED ->
        ItemLook(GcsTheme.colors.emergency, DesignSystemR.drawable.ic_error, R.string.hud_checklist_fail)
}
