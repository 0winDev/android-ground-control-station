package com.owindev.gcs.feature.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ExportTarget
import com.owindev.gcs.core.domain.recording.ImportSource
import com.owindev.gcs.core.domain.recording.RecordingRepository
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The recordings list (REQ-038): replays one (REQ-037), shares, deletes after confirmation, and imports. What each
 * action decides belongs to the repositories; this only shows the outcome. A replay that starts sends the operator to
 * the flight screen; one refused or failed stays here with its reason.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repository: RecordingRepository,
    private val replay: ReplayRepository,
) : ViewModel() {

    private val local = MutableStateFlow(RecordingsUiState())
    private val effects = Channel<RecordingsEffect>(Channel.BUFFERED)

    val state: StateFlow<RecordingsUiState> = combine(repository.recordings, local) { recordings, local ->
        local.copy(recordings = recordings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), RecordingsUiState())

    val effect: Flow<RecordingsEffect> = effects.receiveAsFlow()

    fun onPlay(name: String) {
        val file = repository.fileOf(name) ?: return show(RecordingsMessage.Missing(name))
        when (val started = replay.play(file)) {
            is ReplayState.Playing -> effects.trySend(RecordingsEffect.ReplayStarted)
            else -> show(RecordingsMessage.Replay(started))
        }
    }

    fun onShare(name: String) {
        val file = repository.fileOf(name) ?: return show(RecordingsMessage.Missing(name))
        effects.trySend(RecordingsEffect.Share(file))
    }

    /** Asks the screen for a place to save [name] to. */
    fun onSaveRequested(name: String) {
        effects.trySend(RecordingsEffect.PickSaveTarget(name))
    }

    /** The place picked to save [name] to, `null` when the operator closed the picker. */
    fun onSaveTargetPicked(name: String, target: ExportTarget?) {
        if (target == null) return show(RecordingsMessage.Save(name, SaveResult.Cancelled))
        viewModelScope.launch {
            show(RecordingsMessage.Save(name, repository.saveCopy(name, target)))
        }
    }

    fun onDeleteRequested(name: String) = local.update { it.copy(pendingDelete = name) }

    fun onDeleteCancelled() = local.update { it.copy(pendingDelete = null) }

    fun onDeleteConfirmed() {
        val name = local.value.pendingDelete ?: return
        local.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            val result = repository.delete(name)
            if (result != DeleteResult.Deleted) show(RecordingsMessage.Delete(result))
        }
    }

    fun onImport(source: ImportSource) {
        if (local.value.importing) return
        local.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            val result = repository.import(source)
            local.update { it.copy(importing = false, message = RecordingsMessage.Import(result)) }
        }
    }

    fun onMessageDismissed() = local.update { it.copy(message = null) }

    private fun show(message: RecordingsMessage) = local.update { it.copy(message = message) }

    private companion object {
        val STOP_TIMEOUT = 5.seconds
    }
}
