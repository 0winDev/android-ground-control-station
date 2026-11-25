package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.alert.AlertLevel

/**
 * The vehicle's messages (REQ-047), below every alert and the link-lost emergency (REQ-017, REQ-028): the one at a
 * glance while the vehicle is heard, which opens the list, or a button for the list while the link is lost. The list
 * opens in place, never over the emergency, and shorter when [compact], so it leaves room for the panel under the map.
 * Each line says it is the vehicle's, so a text never reads as one of the app's own alerts.
 */
@Composable
internal fun MessagesSection(
    messages: VehicleMessages,
    listOpen: Boolean,
    onToggleList: () -> Unit,
    compact: Boolean,
) {
    if (messages.kept.isEmpty()) return
    val latest = messages.latest
    if (latest != null) {
        MessageLineCard(line = latest, listOpen = listOpen, onClick = onToggleList)
    } else if (!listOpen) {
        OutlinedButton(onClick = onToggleList, modifier = Modifier.heightIn(min = GcsTheme.sizes.button)) {
            Text(text = stringResource(id = R.string.hud_messages_count, messages.kept.size).uppercase())
        }
    }
    if (listOpen) {
        MessageList(
            messages = messages.kept,
            onClose = onToggleList,
            maxHeight = if (compact) CompactListMaxHeight else ListMaxHeight,
        )
    }
}

/**
 * One message at a glance: outlined in its level's colour, never filled, so it never passes for an alert. TalkBack
 * announces a new message, never its age counting on each second.
 */
@Composable
private fun MessageLineCard(line: MessageLine, listOpen: Boolean, onClick: () -> Unit) {
    val clickLabel = stringResource(id = if (listOpen) R.string.hud_messages_hide else R.string.hud_messages_open)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = levelColor(line.level)),
    ) {
        MessageRow(
            line = line,
            maxLines = LINE_MAX_LINES,
            ageAnnounced = false,
            modifier = Modifier.padding(GcsTheme.spacing.chipPadding),
        )
    }
}

@Composable
private fun MessageList(messages: List<MessageLine>, onClose: () -> Unit, maxHeight: Dp) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(GcsTheme.spacing.chipPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.hud_messages_count, messages.size).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = GcsTheme.sizes.button)) {
                    Text(text = stringResource(id = R.string.hud_messages_close).uppercase())
                }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
            ) {
                messages.forEach { MessageRow(line = it, maxLines = Int.MAX_VALUE, ageAnnounced = true) }
            }
        }
    }
}

/**
 * Icon, source and level in words, the text as plain text and its age: never colour alone, never the text interpreted.
 * The age is left out of what TalkBack reads unless [ageAnnounced].
 */
@Composable
private fun MessageRow(line: MessageLine, maxLines: Int, ageAnnounced: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = styleOf(line.level)
        // Decorative: the level in words follows it.
        Icon(painter = painterResource(id = style.icon), contentDescription = null, tint = levelColor(line.level))
        // The tag above the text, never beside it: on a phone it would leave the text a few letters (lesson #61).
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip)) {
            if (line.recorded) RecordedTag()
            Text(
                text = messageText(line, stringResource(id = style.label)),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(id = R.string.hud_message_age, elapsedText(line.age)),
            style = GcsTheme.telemetryTypography.coordinate,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (ageAnnounced) Modifier else Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun messageText(line: MessageLine, label: String): String {
    val level = line.unknownSeverity?.let { stringResource(id = R.string.hud_message_unknown_level, label, it) }
        ?: label
    val text = if (line.incomplete) stringResource(id = R.string.hud_message_incomplete, line.text) else line.text
    val source = stringResource(id = R.string.hud_message_source)
    return stringResource(
        id = R.string.hud_alert_banner,
        source,
        stringResource(id = R.string.hud_alert_banner, level, text),
    )
}

@Composable
private fun levelColor(level: AlertLevel): Color = when (level) {
    AlertLevel.ADVISORY -> GcsTheme.colors.advisory
    AlertLevel.CAUTION -> GcsTheme.colors.caution
    AlertLevel.EMERGENCY -> GcsTheme.colors.emergency
}

private const val LINE_MAX_LINES = 2
private val ListMaxHeight = 280.dp
private val CompactListMaxHeight = 160.dp
