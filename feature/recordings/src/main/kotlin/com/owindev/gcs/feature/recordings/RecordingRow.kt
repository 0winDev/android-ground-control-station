package com.owindev.gcs.feature.recordings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.recording.RecordingSummary
import java.text.DateFormat
import java.util.Date

/** One recording: when it started, its file name, how long it runs and its size, then what can be done with it. */
@Composable
internal fun RecordingRow(recording: RecordingSummary, actions: RecordingActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RowShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(all = ContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(ContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = startText(recording), style = GcsTheme.telemetryTypography.value)
                Text(
                    text = recording.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                    Field(
                        R.string.recordings_column_duration,
                        recording.duration?.let { elapsedText(it) } ?: stringResource(R.string.recordings_no_value),
                    )
                    Field(R.string.recordings_column_size, sizeText(recording.sizeBytes))
                }
            }
            RowActions(recording, actions)
        }
    }
}

/**
 * The recording in progress offers nothing; the one being replayed can be played again, shared or saved, never
 * deleted.
 * Delete is outlined in the emergency colour and always last, apart from Play.
 */
@Composable
private fun RowActions(recording: RecordingSummary, actions: RecordingActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(ContentSpacing), verticalAlignment = Alignment.CenterVertically) {
        when {
            recording.inProgress -> StatusChip(stringResource(R.string.recordings_in_progress))

            else -> {
                if (recording.replaying) StatusChip(stringResource(R.string.recordings_replaying))
                Button(
                    onClick = { actions.onPlay(recording.name) },
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                ) {
                    Text(text = stringResource(R.string.recordings_play).uppercase())
                }
                OutlinedButton(
                    onClick = { actions.onShare(recording.name) },
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                ) {
                    Text(text = stringResource(R.string.recordings_share).uppercase())
                }
                OutlinedButton(
                    onClick = { actions.onSave(recording.name) },
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                ) {
                    Text(text = stringResource(R.string.recordings_save).uppercase())
                }
                if (!recording.replaying) {
                    OutlinedButton(
                        onClick = { actions.onDeleteRequest(recording.name) },
                        modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                        border = BorderStroke(width = 1.dp, color = GcsTheme.colors.emergency),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = GcsTheme.colors.onEmergencyContainer,
                        ),
                    ) {
                        Text(text = stringResource(R.string.recordings_delete).uppercase())
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String) {
    Column {
        Text(
            text = stringResource(label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = GcsTheme.telemetryTypography.coordinate)
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(shape = RowShape, color = GcsTheme.colors.advisoryContainer) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = ContentSpacing, vertical = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            color = GcsTheme.colors.advisory,
        )
    }
}

/** The first valid frame's wall-clock time, in the device's locale and zone. */
@Composable
private fun startText(recording: RecordingSummary): String {
    val start = recording.startMillis ?: return stringResource(R.string.recordings_no_frames)
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(start))
}

@Composable
internal fun sizeText(bytes: Long): String {
    val size = mebibytesOf(bytes)
    return stringResource(size.format.text, size.mebibytes)
}
