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

// How the pre-flight checklist says each item (REQ-048): a title per kind, a reason in words, a colour with its icon
// and word.

@Composable
internal fun reasonText(entry: ChecklistItem): String = when (val reason = entry.item.reason) {
    is PreflightReason.PreArmFailing -> preArmText(reason, entry)

    is PreflightReason.BatteryUnknown -> stringResource(
        id = when (reason.cause) {
            BatteryUnknownCause.NO_MONITOR -> R.string.hud_reason_battery_no_monitor
            BatteryUnknownCause.NO_READING -> R.string.hud_reason_battery_no_reading
        },
    )

    is PreflightReason.FailsafeNotArmed -> stringResource(
        id = R.string.hud_reason_failsafe_not_armed,
        stringResource(id = reason.low.words()),
        stringResource(id = reason.critical.words()),
    )

    else -> ReasonWords[reason]?.let { stringResource(id = it) }.orEmpty()
}

@Composable
private fun preArmText(reason: PreflightReason.PreArmFailing, entry: ChecklistItem): String {
    val text = reason.text
    val age = entry.reasonAge
    return when {
        text == null || age == null -> stringResource(id = R.string.hud_reason_prearm_failing_no_text)
        reason.current -> stringResource(id = R.string.hud_reason_prearm_failing, text, elapsedText(age))
        else -> stringResource(id = R.string.hud_reason_prearm_failing_old, text, elapsedText(age))
    }
}

/** The words of each reason with no value in it; [reasonText] writes the others with their values. */
private val ReasonWords: Map<PreflightReason, Int> = mapOf(
    PreflightReason.Ok to R.string.hud_reason_ok,
    PreflightReason.NotReceived to R.string.hud_reason_not_received,
    PreflightReason.Stale to R.string.hud_reason_stale,
    PreflightReason.Replay to R.string.hud_reason_replay,
    PreflightReason.NoActiveVehicle to R.string.hud_reason_no_active_vehicle,
    PreflightReason.LinkLost to R.string.hud_reason_link_lost,
    PreflightReason.UplinkSendsFailing to R.string.hud_reason_uplink_sends_failing,
    PreflightReason.UplinkCommandUnanswered to R.string.hud_reason_uplink_command_unanswered,
    PreflightReason.NotAnsweredYet to R.string.hud_reason_not_answered_yet,
    PreflightReason.BackgroundLinkNotRunning to R.string.hud_reason_background_not_running,
    PreflightReason.BackgroundRestricted to R.string.hud_reason_background_restricted,
    PreflightReason.PreArmStateUnknown to R.string.hud_reason_prearm_unknown,
    PreflightReason.ArmingChecksDisabled to R.string.hud_reason_arming_checks_disabled,
    PreflightReason.NoFix3d to R.string.hud_reason_no_fix_3d,
    PreflightReason.BatteryUnhealthy to R.string.hud_reason_battery_unhealthy,
    PreflightReason.BatteryCritical to R.string.hud_reason_battery_critical,
    PreflightReason.BatteryLow to R.string.hud_reason_battery_low,
    PreflightReason.LimitsNotRead to R.string.hud_reason_limits_not_read,
    PreflightReason.HomeUnknown to R.string.hud_reason_home_unknown,
    PreflightReason.EmergencyAlert to R.string.hud_reason_emergency_alert,
    PreflightReason.AlertsNotEvaluated to R.string.hud_reason_alerts_not_evaluated,
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
