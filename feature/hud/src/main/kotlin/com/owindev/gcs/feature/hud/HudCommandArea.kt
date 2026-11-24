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
import androidx.compose.ui.platform.LocalResources
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
 * The last command's status above the bar: never in the sheet, which is modal, so RTL and land stay a tap away while
 * another command waits for its answer.
 */
@Composable
internal fun CommandArea(
    bar: CommandBar,
    feedback: CommandFeedback?,
    actions: CommandActions,
    refusal: CommandFeedback? = null,
    route: RouteNotice? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GcsTheme.spacing.screen, vertical = GcsTheme.spacing.chip),
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
    ) {
        route?.let { RouteLine(notice = it, onReview = actions.onReviewRoute) }
        refusal?.let { FeedbackLine(feedback = it, onDismiss = actions.onDismissRefusal) }
        feedback?.let { FeedbackLine(feedback = it, onDismiss = actions.onDismissFeedback) }
        // The ground command sits apart from RTL and land: arming never leaves a finger where RTL appears next.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
        ) {
            CommandButton(offered = bar.ground, onRequest = actions.onRequest, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(GcsTheme.spacing.screen))
            CommandButton(
                offered = bar.returnToLaunch,
                onRequest = actions.onRequest,
                modifier = Modifier.weight(1f),
                emphasised = true,
            )
            CommandButton(offered = bar.land, onRequest = actions.onRequest, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CommandButton(
    offered: OfferedCommand,
    onRequest: (VehicleCommand) -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val enabled = offered.unavailable == null
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> colors.onSurfaceVariant
        emphasised -> GcsTheme.colors.caution
        else -> colors.onSurface
    }
    val title = stringResource(id = offered.command.title())
    val reason = offered.unavailable?.let { unavailableText(it, offered.command) }
    Surface(
        onClick = { onRequest(offered.command) },
        enabled = enabled || offered.opensChecklist,
        modifier = modifier
            .heightIn(min = GcsTheme.sizes.gloveButton)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, reason).joinToString(separator = ". ")
            },
        shape = ChipShape,
        color = if (emphasised && enabled) GcsTheme.colors.cautionContainer else colors.surfaceContainerHigh,
        border = BorderStroke(
            width = if (emphasised && enabled) EmphasisedBorder else NormalBorder,
            color = if (emphasised && enabled) GcsTheme.colors.caution else colors.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(all = GcsTheme.spacing.chip),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title.uppercase(), color = content, style = MaterialTheme.typography.titleMedium)
            reason?.let { Text(text = it, color = content, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FeedbackLine(feedback: CommandFeedback, onDismiss: () -> Unit) {
    val (text, level) = feedbackText(feedback)
    val (container, onContainer) = levelColors(level)
    Surface(modifier = Modifier.fillMaxWidth(), shape = ChipShape, color = container) {
        Row(
            modifier = Modifier.padding(start = GcsTheme.spacing.chipPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = GcsTheme.spacing.chip),
                color = onContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (feedback.status is CommandStatus.Ended) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget),
                ) {
                    Text(text = stringResource(id = R.string.hud_command_dismiss).uppercase(), color = onContainer)
                }
            }
        }
    }
}

@Composable
internal fun unavailableText(unavailable: CommandUnavailable, command: VehicleCommand): String = when (unavailable) {
    CommandUnavailable.NotOnGround -> stringResource(
        id = if (command ==
            VehicleCommand.TAKE_OFF
        ) {
            R.string.hud_command_only_on_ground
        } else {
            R.string.hud_command_not_on_ground
        },
    )

    CommandUnavailable.NotArmed -> stringResource(id = R.string.hud_command_only_armed)

    is CommandUnavailable.PreflightRed ->
        pluralStringResource(id = R.plurals.hud_command_preflight_red, count = unavailable.items, unavailable.items)

    CommandUnavailable.CautionsToAcknowledge -> stringResource(id = R.string.hud_command_acknowledge_cautions)

    CommandUnavailable.RouteChanged -> stringResource(id = R.string.hud_command_route_changed)

    CommandUnavailable.RouteAlternating -> stringResource(id = R.string.hud_command_route_alternating)

    CommandUnavailable.RouteUnknown -> stringResource(id = R.string.hud_command_route_unknown)
}

@Composable
private fun feedbackText(feedback: CommandFeedback): Pair<String, CommandMessage.Level> {
    val name = stringResource(id = feedback.command.title())
    return when (val status = feedback.status) {
        is CommandStatus.Sending -> if (status.attempt == null) {
            stringResource(id = R.string.hud_command_sending, name)
        } else {
            stringResource(id = R.string.hud_command_sending_attempt, name, status.attempt, status.attempts)
        } to CommandMessage.Level.ADVISORY

        is CommandStatus.InProgress -> if (status.percent == null) {
            stringResource(id = R.string.hud_command_in_progress, name)
        } else {
            stringResource(id = R.string.hud_command_in_progress_percent, name, status.percent)
        } to CommandMessage.Level.ADVISORY

        is CommandStatus.Ended -> messageText(status.message) to status.message.level
    }
}

// The arguments of one short message, built once per state.
@Suppress("SpreadOperator")
@Composable
private fun messageText(message: CommandMessage): String {
    val resources = LocalResources.current
    val args = message.args.map { arg -> if (arg is TextArg) resources.getString(arg.text) else arg }
    return message.quantity?.let { resources.getQuantityString(message.text, it, *args.toTypedArray()) }
        ?: resources.getString(message.text, *args.toTypedArray())
}

@Composable
private fun levelColors(level: CommandMessage.Level): Pair<Color, Color> = when (level) {
    CommandMessage.Level.NOMINAL -> GcsTheme.colors.nominalContainer to MaterialTheme.colorScheme.onSurface
    CommandMessage.Level.ADVISORY -> GcsTheme.colors.advisoryContainer to MaterialTheme.colorScheme.onSurface
    CommandMessage.Level.CAUTION -> GcsTheme.colors.cautionContainer to MaterialTheme.colorScheme.onSurface
    CommandMessage.Level.EMERGENCY -> GcsTheme.colors.emergencyContainer to GcsTheme.colors.onEmergencyContainer
}

internal val NormalBorder = 1.dp
private val EmphasisedBorder = 2.dp
