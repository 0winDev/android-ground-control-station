package com.owindev.gcs.feature.recordings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRefusal
import com.owindev.gcs.core.domain.replay.ReplayState

/** The outcome of the last action, until dismissed: green for an import or a copy done, amber for anything else. */
@Composable
internal fun MessageCard(message: RecordingsMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val done = (message is RecordingsMessage.Import && message.result is ImportResult.Imported) ||
        (message is RecordingsMessage.Save && message.result == SaveResult.Saved)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RowShape,
        color = if (done) GcsTheme.colors.nominalContainer else GcsTheme.colors.cautionContainer,
    ) {
        Row(
            modifier = Modifier.padding(all = GcsTheme.spacing.content),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = messageText(message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget)) {
                Text(text = stringResource(R.string.recordings_dismiss).uppercase())
            }
        }
    }
}

/** A delete never happens on the first tap; Keep is the dismissal. */
@Composable
internal fun DeleteDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(R.string.recordings_delete_title)) },
        text = { Text(text = stringResource(R.string.recordings_delete_body, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget)) {
                Text(
                    text = stringResource(R.string.recordings_delete_confirm).uppercase(),
                    color = GcsTheme.colors.onEmergencyContainer,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget)) {
                Text(text = stringResource(R.string.recordings_delete_cancel).uppercase())
            }
        },
    )
}

@Composable
private fun messageText(message: RecordingsMessage): String = when (message) {
    is RecordingsMessage.Import -> importText(message.result)
    is RecordingsMessage.Delete -> stringResource(deleteText(message.result))
    is RecordingsMessage.Replay -> stringResource(replayText(message.state))
    is RecordingsMessage.ExportInProgress -> stringResource(R.string.recordings_export_in_progress)
    is RecordingsMessage.Save -> saveText(message)
    is RecordingsMessage.Missing -> stringResource(R.string.recordings_missing, message.name)
}

/** An import done names the old recordings the limit deleted to make room for it (REQ-039). */
@Composable
private fun importText(result: ImportResult): String = when (result) {
    is ImportResult.Imported -> buildString {
        append(stringResource(R.string.recordings_imported, result.name))
        if (result.deleted.isNotEmpty()) {
            append(' ')
            append(stringResource(R.string.recordings_imported_deleted, result.deleted.joinToString()))
        }
    }

    is ImportResult.TooLarge -> when (val declared = result.declaredBytes) {
        null -> stringResource(R.string.recordings_import_too_large, sizeText(result.maxBytes))

        else -> stringResource(
            R.string.recordings_import_too_large_sized,
            sizeText(declared),
            sizeText(result.maxBytes),
        )
    }

    ImportResult.NotATlog -> stringResource(R.string.recordings_import_not_tlog)

    ImportResult.NoRoom -> stringResource(R.string.recordings_import_no_room)

    ImportResult.Recording -> stringResource(R.string.recordings_import_recording)

    ImportResult.Failed -> stringResource(R.string.recordings_import_failed)
}

@Composable
private fun saveText(message: RecordingsMessage.Save): String = when (message.result) {
    SaveResult.Saved -> stringResource(R.string.recordings_saved, message.name)
    SaveResult.Cancelled -> stringResource(R.string.recordings_save_cancelled)
    SaveResult.InProgress -> stringResource(R.string.recordings_export_in_progress)
    SaveResult.Failed -> stringResource(R.string.recordings_save_failed)
}

@StringRes
private fun deleteText(result: DeleteResult): Int = when (result) {
    DeleteResult.InProgress -> R.string.recordings_delete_in_progress
    DeleteResult.Replaying -> R.string.recordings_delete_replaying
    DeleteResult.Failed -> R.string.recordings_delete_failed
    DeleteResult.NotFound, DeleteResult.Deleted -> R.string.recordings_delete_not_found
}

/** Only a refused or failed replay is ever shown here; one that starts leaves the screen. */
@StringRes
private fun replayText(state: ReplayState): Int = when (state) {
    is ReplayState.Refused -> when (state.reason) {
        ReplayRefusal.VEHICLE_CONNECTED -> R.string.recordings_replay_connected
        ReplayRefusal.VEHICLE_ARMED -> R.string.recordings_replay_armed
    }

    is ReplayState.Failed -> when (state.reason) {
        ReplayFailure.NOT_A_RECORDING -> R.string.recordings_replay_not_a_recording
        ReplayFailure.UNREADABLE -> R.string.recordings_replay_unreadable
    }

    else -> R.string.recordings_replay_unreadable
}
