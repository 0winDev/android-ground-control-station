package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.owindev.gcs.core.designsystem.confirm.SlideToConfirm
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import kotlin.math.roundToInt

/**
 * The confirmation (REQ-050): what the command does, the take off altitude and the slide naming the action and the
 * vehicle. Back or a tap outside cancels it; nothing is sent but by the slide.
 */
@Composable
internal fun CommandSheetDialog(sheet: CommandSheet, actions: CommandActions) {
    Dialog(
        onDismissRequest = actions.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .widthIn(max = SheetMaxWidth)
                    .fillMaxWidth(),
                shape = SheetShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(NormalBorder, MaterialTheme.colorScheme.outline),
            ) {
                SheetContent(sheet = sheet, actions = actions)
            }
        }
    }
}

@Composable
private fun SheetContent(sheet: CommandSheet, actions: CommandActions) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(all = GcsTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
    ) {
        Column {
            Text(
                text = stringResource(id = sheet.command.title()),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(id = R.string.hud_command_dangerous),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(text = stringResource(id = sheet.command.description()), style = MaterialTheme.typography.bodyLarge)
        sheet.altitudeMetres?.let { AltitudeStepper(metres = it, onChange = actions.onAltitudeChanged) }
        if (sheet.slideAgain) {
            Text(
                text = stringResource(id = R.string.hud_command_slide_again),
                color = GcsTheme.colors.caution,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        SlideToConfirm(
            action = stringResource(id = sheet.command.action()),
            systemId = sheet.systemId,
            // No key while it cannot be confirmed: the control is disabled then, so this one is never handed back.
            confirmKey = sheet.key ?: Unit,
            onConfirm = { key -> (key as? ConfirmKey)?.let(actions.onConfirm) },
            disabledReason = sheet.unavailable?.let { unavailableText(it, sheet.command) },
        )
        Text(
            text = stringResource(id = R.string.hud_command_slide_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun AltitudeStepper(metres: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(id = R.string.hud_command_altitude),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        StepButton(
            label = "−",
            description = stringResource(id = R.string.hud_command_altitude_lower),
            onClick = { onChange(metres - ALTITUDE_STEP) },
        )
        Text(
            text = stringResource(id = R.string.hud_command_altitude_value, metres.roundToInt()),
            modifier = Modifier.padding(horizontal = GcsTheme.spacing.content),
            style = GcsTheme.telemetryTypography.value,
        )
        StepButton(
            label = "+",
            description = stringResource(id = R.string.hud_command_altitude_higher),
            onClick = { onChange(metres + ALTITUDE_STEP) },
        )
    }
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .size(GcsTheme.sizes.button)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        shape = ChipShape,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

private fun VehicleCommand.description(): Int = when (this) {
    VehicleCommand.ARM -> R.string.hud_command_describe_arm
    VehicleCommand.TAKE_OFF -> R.string.hud_command_describe_take_off
    VehicleCommand.RETURN_TO_LAUNCH -> R.string.hud_command_describe_rtl
    VehicleCommand.LAND -> R.string.hud_command_describe_land
}

private fun VehicleCommand.action(): Int = when (this) {
    VehicleCommand.ARM -> R.string.hud_command_action_arm
    VehicleCommand.TAKE_OFF -> R.string.hud_command_action_take_off
    VehicleCommand.RETURN_TO_LAUNCH -> R.string.hud_command_action_rtl
    VehicleCommand.LAND -> R.string.hud_command_action_land
}

private const val ALTITUDE_STEP = 1f
private val SheetMaxWidth = 640.dp
private val SheetShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

@Preview(widthDp = 360)
@Composable
private fun CommandsNightPreview() {
    CommandsPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360)
@Composable
private fun CommandsDayPreview() {
    CommandsPreview(palette = GcsPalette.DAY)
}

@Composable
private fun CommandsPreview(palette: GcsPalette) {
    GcsTheme(palette = palette) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                SheetContent(
                    sheet = CommandSheet(
                        command = VehicleCommand.TAKE_OFF,
                        systemId = 1,
                        altitudeMetres = 10f,
                        key = ConfirmKey(
                            VehicleCommand.TAKE_OFF,
                            CommandKey(SystemId(1), connection = 1),
                            altitudeMetres = 10f,
                        ),
                        unavailable = null,
                    ),
                    actions = CommandActions(),
                )
                CommandArea(
                    bar = CommandBar(
                        ground = OfferedCommand(VehicleCommand.TAKE_OFF, CommandUnavailable.NotOnGround),
                        returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH),
                        land = OfferedCommand(VehicleCommand.LAND),
                    ),
                    feedback = CommandFeedback(
                        VehicleCommand.RETURN_TO_LAUNCH,
                        CommandStatus.Sending(attempt = 2, attempts = 3),
                    ),
                    actions = CommandActions(),
                )
            }
        }
    }
}
