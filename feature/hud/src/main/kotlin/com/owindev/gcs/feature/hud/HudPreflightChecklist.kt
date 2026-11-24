package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.owindev.gcs.core.designsystem.confirm.SlideToConfirm
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import kotlin.time.Duration.Companion.seconds

/**
 * Arm's sheet (REQ-048): every pre-flight item with its colour, icon, word and reason, red first, an acknowledgement
 * for each amber one, and the slide only when nothing holds arming; otherwise the reason takes its place. The list
 * scrolls while the slide or its reason stays in view.
 */
@Composable
internal fun ChecklistSheetContent(sheet: CommandSheet, checklist: List<ChecklistItem>, actions: CommandActions) {
    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(all = GcsTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
    ) {
        ChecklistHeader(checklist)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
        ) {
            checklist.forEach { ChecklistRow(entry = it, onAcknowledge = actions.onAcknowledgeCaution) }
        }
        if (sheet.slideAgain) {
            Text(
                text = stringResource(id = R.string.hud_command_slide_again),
                color = GcsTheme.colors.caution,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val key = sheet.key
        if (key != null) {
            SlideToConfirm(
                action = stringResource(id = R.string.hud_command_action_arm),
                systemId = sheet.systemId,
                confirmKey = key,
                onConfirm = actions.onConfirm,
            )
        } else {
            sheet.unavailable?.let { ArmHeldBy(it) }
        }
        OutlinedButton(
            onClick = actions.onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GcsTheme.sizes.button),
        ) {
            Text(text = stringResource(id = R.string.hud_command_cancel).uppercase())
        }
    }
}

@Composable
private fun ChecklistHeader(checklist: List<ChecklistItem>) {
    Column {
        Text(text = stringResource(id = VehicleCommand.ARM.title()), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(id = R.string.hud_checklist_title) + " · " + stringResource(
                id = R.string.hud_checklist_summary,
                checklist.count { it.item.colour == PreflightColour.RED },
                checklist.count { it.item.colour == PreflightColour.AMBER },
                checklist.count { it.item.colour == PreflightColour.GREEN },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChecklistRow(entry: ChecklistItem, onAcknowledge: (PreflightItem) -> Unit) {
    val look = entry.item.colour.look()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GcsTheme.sizes.minTouchTarget)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(id = look.icon), contentDescription = null, tint = look.color)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(id = entry.item.kind.title()), style = MaterialTheme.typography.titleSmall)
            if (entry.item.reason != PreflightReason.Ok) {
                Text(
                    text = reasonText(entry),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (entry.item.colour == PreflightColour.AMBER) {
                if (entry.acknowledged) {
                    Text(
                        text = stringResource(id = R.string.hud_checklist_acknowledged),
                        color = GcsTheme.colors.caution,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    OutlinedButton(
                        onClick = { onAcknowledge(entry.item) },
                        modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                    ) {
                        Text(text = stringResource(id = R.string.hud_checklist_acknowledge).uppercase())
                    }
                }
            }
        }
        Text(
            text = stringResource(id = look.word).uppercase(),
            color = look.color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** What holds arming, in the slide's place: never a slide that only looks disabled. */
@Composable
private fun ArmHeldBy(unavailable: CommandUnavailable) {
    val look = if (unavailable is CommandUnavailable.PreflightRed) {
        PreflightColour.RED.look()
    } else {
        PreflightColour.AMBER.look()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GcsTheme.sizes.minTouchTarget)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(id = look.icon), contentDescription = null, tint = look.color)
        Text(
            text = unavailableText(unavailable, VehicleCommand.ARM),
            color = look.color,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ChecklistNightPreview() {
    ChecklistPreview(palette = GcsPalette.NIGHT, held = true)
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ChecklistDayPreview() {
    ChecklistPreview(palette = GcsPalette.DAY, held = false)
}

@Composable
private fun ChecklistPreview(palette: GcsPalette, held: Boolean) {
    val key = CommandKey(SystemId(1), connection = 1)
    val red = PreflightItem(
        PreflightKind.VEHICLE_PREARM,
        PreflightColour.RED,
        PreflightReason.PreArmFailing("PreArm: GPS 1: Bad fix", current = true),
    )
    val amber = PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead)
    val greens = listOf(PreflightKind.LINK, PreflightKind.UPLINK, PreflightKind.GPS, PreflightKind.HOME).map {
        ChecklistItem(PreflightItem(it, PreflightColour.GREEN, PreflightReason.Ok))
    }
    val checklist = if (held) {
        listOf(ChecklistItem(red, reasonAge = 4.seconds), ChecklistItem(amber)) + greens
    } else {
        listOf(ChecklistItem(amber, acknowledged = true)) + greens
    }
    GcsTheme(palette = palette) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ChecklistSheetContent(
                sheet = CommandSheet(
                    command = VehicleCommand.ARM,
                    systemId = 1,
                    altitudeMetres = null,
                    key = if (held) null else ConfirmKey(VehicleCommand.ARM, key, altitudeMetres = null),
                    unavailable = if (held) CommandUnavailable.PreflightRed(items = 1) else null,
                    checklist = checklist,
                ),
                checklist = checklist,
                actions = CommandActions(),
            )
        }
    }
}
