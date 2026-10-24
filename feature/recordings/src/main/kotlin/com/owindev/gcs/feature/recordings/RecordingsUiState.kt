package com.owindev.gcs.feature.recordings

import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.RecordingSummary
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayState
import java.io.File

/**
 * The recordings screen: the [recordings], newest first (`null` until first read), the one waiting for the operator
 * to confirm its deletion, whether an import is running, and the [message] left by the last action until dismissed.
 */
data class RecordingsUiState(
    val recordings: List<RecordingSummary>? = null,
    val pendingDelete: String? = null,
    val importing: Boolean = false,
    val message: RecordingsMessage? = null,
)

internal fun RecordingsUiState.isInProgress(name: String): Boolean =
    recordings.orEmpty().any { it.name == name && it.inProgress }

/** What an action left for the operator to read; a failure stays until dismissed. */
sealed interface RecordingsMessage {

    /** Any import result, success included, since it may name the old recordings deleted to make room (REQ-039). */
    data class Import(val result: ImportResult) : RecordingsMessage

    /** A delete that did not happen. */
    data class Delete(val result: DeleteResult) : RecordingsMessage

    /** A replay that did not start: [ReplayState.Refused] or [ReplayState.Failed]. */
    data class Replay(val state: ReplayState) : RecordingsMessage

    /** The recording [name] is still being recorded, so it is neither shared nor saved yet. */
    data class ExportInProgress(val name: String) : RecordingsMessage

    /** How saving a copy of [name] to the device went, a closed picker included. */
    data class Save(val name: String, val result: SaveResult) : RecordingsMessage

    data class Missing(val name: String) : RecordingsMessage
}

/** One-off actions for the screen: leaving for the flight screen, sharing a file (`:app`), picking where to save. */
sealed interface RecordingsEffect {

    data object ReplayStarted : RecordingsEffect

    data class Share(val recording: File) : RecordingsEffect

    /** Open the system picker to create a document for a copy of [name], suggested under that name. */
    data class PickSaveTarget(val name: String) : RecordingsEffect
}
