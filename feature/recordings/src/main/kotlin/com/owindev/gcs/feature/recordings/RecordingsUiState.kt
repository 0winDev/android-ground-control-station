package com.owindev.gcs.feature.recordings

import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.RecordingSummary
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

/** What an action left for the operator to read; a failure stays until dismissed. */
sealed interface RecordingsMessage {

    /** Any import result, success included, since it may name the old recordings deleted to make room (REQ-039). */
    data class Import(val result: ImportResult) : RecordingsMessage

    /** A delete that did not happen. */
    data class Delete(val result: DeleteResult) : RecordingsMessage

    /** A replay that did not start: [ReplayState.Refused] or [ReplayState.Failed]. */
    data class Replay(val state: ReplayState) : RecordingsMessage

    /** The recording [name] is no longer there. */
    data class Missing(val name: String) : RecordingsMessage
}

/** One-off actions the screen hands to `:app`: leaving for the flight screen, sharing a file. */
sealed interface RecordingsEffect {

    data object ReplayStarted : RecordingsEffect

    data class Share(val recording: File) : RecordingsEffect
}
