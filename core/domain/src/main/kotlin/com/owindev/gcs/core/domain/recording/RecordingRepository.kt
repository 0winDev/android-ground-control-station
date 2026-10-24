package com.owindev.gcs.core.domain.recording

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * The flight recordings on the device, recorded or imported (REQ-036, REQ-038). Implemented in `:data:vehicle` and
 * bound there with Hilt.
 *
 * The recording in progress and the one being replayed are never deleted, and nothing is imported while a flight is
 * recorded. An import is untrusted input: it never throws, and whatever it refuses leaves the recordings as they were.
 */
interface RecordingRepository {

    /** The recordings, newest first, refreshed when one opens, closes, is imported or deleted. */
    val recordings: Flow<List<RecordingSummary>>

    /** The recording named [name] by its real path, for a replay or an export; `null` if it is not one. */
    suspend fun fileOf(name: String): File?

    suspend fun delete(name: String): DeleteResult

    /** Copies [source] into the recordings under a name of its own (see [ImportRule]). */
    suspend fun import(source: ImportSource): ImportResult

    /** Writes a copy of the recording [name] to [target], outside the app; never the recording in progress. */
    suspend fun saveCopy(name: String, target: ExportTarget): SaveResult
}

/** A place outside the app the operator picked to save a recording to. */
interface ExportTarget {

    @Throws(IOException::class)
    fun open(): OutputStream
}

sealed interface SaveResult {

    data object Saved : SaveResult

    /** The operator closed the picker without choosing a place. */
    data object Cancelled : SaveResult

    /** It is being recorded now. */
    data object InProgress : SaveResult

    /** The recording is no longer there, or reading it or writing the copy failed. */
    data object Failed : SaveResult
}

/**
 * A recording in the list: when its first record was written ([startMillis], Unix epoch, wall clock) and how long it
 * runs to its last record, both `null` when it holds no valid MAVLink 2 frame.
 */
data class RecordingSummary(
    val name: String,
    val startMillis: Long?,
    val duration: Duration?,
    val sizeBytes: Long,
    val inProgress: Boolean,
    val replaying: Boolean,
)

/** A file to import, from outside the app; its [displayName] and [declaredBytes] come from its source, unchecked. */
interface ImportSource {

    val displayName: String?

    /** `null` or negative when the source does not say. */
    val declaredBytes: Long?

    @Throws(IOException::class)
    fun open(): InputStream
}

sealed interface DeleteResult {

    data object Deleted : DeleteResult

    /** It is being recorded now. */
    data object InProgress : DeleteResult

    /** It is being replayed now. */
    data object Replaying : DeleteResult

    data object NotFound : DeleteResult

    /** The file system refused it. */
    data object Failed : DeleteResult
}

sealed interface ImportResult {

    /** Stored as [name]; [deleted] are the oldest recordings removed to keep within the limit (REQ-039). */
    data class Imported(val name: String, val deleted: List<String>) : ImportResult

    /** Larger than [maxBytes]: [declaredBytes] when its source said so, `null` when counted while copying. */
    data class TooLarge(val maxBytes: Long, val declaredBytes: Long? = null) : ImportResult

    /** It holds no valid MAVLink 2 frame in .tlog records. */
    data object NotATlog : ImportResult

    /** The last one closed and the one replayed leave it no room within the limit. */
    data object NoRoom : ImportResult

    /** A flight is being recorded: an import could fill the disk under its black box. */
    data object Recording : ImportResult

    /** Reading the source or writing the copy failed. */
    data object Failed : ImportResult
}
