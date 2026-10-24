package com.owindev.gcs.feature.recordings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.RecordingSummary
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRefusal
import com.owindev.gcs.core.domain.replay.ReplayState
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The recordings list (REQ-038): play one on the flight screen (REQ-037), export it through the share sheet, delete
 * it after confirmation, or import a .tlog with the system file picker. `:app` opens it only while no live vehicle is
 * armed and leaves it on its own when one arms or an emergency is raised (ADR 0007).
 */
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onReplayStart: () -> Unit,
    onShare: (File) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnReplayStart by rememberUpdatedState(onReplayStart)
    val currentOnShare by rememberUpdatedState(onShare)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                RecordingsEffect.ReplayStarted -> currentOnReplayStart()
                is RecordingsEffect.Share -> currentOnShare(effect.recording)
            }
        }
    }
    val resolver = LocalContext.current.contentResolver
    // A .tlog has no registered MIME type, so the picker shows every file; the import checks what it gets.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onImport(ContentImportSource(resolver, it)) }
    }
    RecordingsContent(
        state = state,
        actions = RecordingsActions(
            onBack = onBack,
            onImport = { picker.launch(arrayOf(ANY_TYPE)) },
            onPlay = viewModel::onPlay,
            onShare = viewModel::onShare,
            onDeleteRequest = viewModel::onDeleteRequested,
            onMessageDismiss = viewModel::onMessageDismissed,
        ),
        onDeleteConfirm = viewModel::onDeleteConfirmed,
        onDeleteCancel = viewModel::onDeleteCancelled,
        modifier = modifier,
    )
}

/** What the operator can do on the screen. */
internal class RecordingsActions(
    val onBack: () -> Unit,
    val onImport: () -> Unit,
    val onPlay: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onDeleteRequest: (String) -> Unit,
    val onMessageDismiss: () -> Unit,
)

@Composable
internal fun RecordingsContent(
    state: RecordingsUiState,
    actions: RecordingsActions,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(all = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ContentSpacing),
        ) {
            Header(state, actions)
            state.message?.let { MessageCard(it, onDismiss = actions.onMessageDismiss) }
            val recordings = state.recordings
            when {
                recordings == null -> Note(stringResource(R.string.recordings_loading))

                recordings.isEmpty() -> Note(stringResource(R.string.recordings_empty))

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ContentSpacing),
                    contentPadding = PaddingValues(bottom = ScreenPadding),
                ) {
                    items(recordings, key = RecordingSummary::name) { RecordingRow(it, actions) }
                }
            }
        }
        state.pendingDelete?.let { name ->
            DeleteDialog(name, onConfirm = onDeleteConfirm, onCancel = onDeleteCancel)
        }
    }
}

@Composable
private fun Header(state: RecordingsUiState, actions: RecordingsActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ContentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backDescription = stringResource(R.string.recordings_back_description)
        OutlinedButton(
            onClick = actions.onBack,
            modifier = Modifier
                .heightIn(min = GcsTheme.sizes.button)
                .semantics { contentDescription = backDescription },
        ) {
            Text(text = stringResource(R.string.recordings_back).uppercase())
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.recordings_title).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
            )
            state.recordings?.let { recordings ->
                Text(
                    text = pluralStringResource(R.plurals.recordings_count, recordings.size, recordings.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = actions.onImport,
            enabled = !state.importing,
            modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
        ) {
            val label = if (state.importing) R.string.recordings_importing else R.string.recordings_import
            Text(text = stringResource(label).uppercase())
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val ANY_TYPE = "*/*"
private val ScreenPadding = 24.dp
internal val ContentSpacing = 12.dp
internal val RowShape = RoundedCornerShape(8.dp)
