package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ExportTarget
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.ImportSource
import com.owindev.gcs.core.domain.recording.RecordingRepository
import com.owindev.gcs.core.domain.recording.RecordingSummary
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The recordings in the [folder] (REQ-036, REQ-038): listed with the span of their valid frames, deleted unless being
 * recorded or replayed, and imported from outside under the [rule].
 *
 * An import is untrusted input (`docs/security.md` K-1, K-2). It is refused when its source declares more than the
 * limit, copied into a hidden temporary file in the folder while its bytes are counted against the limit, scanned as
 * the replay reads it, and admitted only if it holds a valid MAVLink 2 frame and fits within the limit with the
 * recordings kept; the temporary file is always deleted. Nothing it holds or its source does makes it throw.
 *
 * The list ([RecordingSummaries]) is read again when the recorder opens or closes a log, a replay starts or stops, or
 * an import or a delete changes the folder. Files are read on [ioDispatcher].
 */
internal class DefaultRecordingRepository(
    private val folder: RecordingsFolder,
    recorder: FlightRecorder,
    replay: ReplayRepository,
    private val rule: ImportRule,
    private val ioDispatcher: CoroutineDispatcher,
) : RecordingRepository {

    private val changes = MutableStateFlow(0)
    private val imports = Mutex()
    private val summaries = RecordingSummaries(folder)

    override val recordings: Flow<List<RecordingSummary>> =
        combine(changes, recorder.status, replay.state) { _, _, _ -> summaries.all() }.flowOn(ioDispatcher)

    override fun fileOf(name: String): File? = folder.recordingAt(File(folder.directory, name))

    override suspend fun delete(name: String): DeleteResult = withContext(ioDispatcher) {
        folder.delete(name).also { if (it == DeleteResult.Deleted) changed() }
    }

    override suspend fun import(source: ImportSource): ImportResult = withContext(ioDispatcher) {
        if (rule.refusesDeclared(source.declaredBytes)) {
            return@withContext ImportResult.TooLarge(rule.maxBytes, source.declaredBytes)
        }
        imports.withLock {
            deleteLeftovers()
            val temporary = newTemporary() ?: return@withLock ImportResult.Failed
            try {
                admit(source, temporary)
            } finally {
                temporary.delete()
            }
        }
    }

    override suspend fun saveCopy(name: String, target: ExportTarget): SaveResult = withContext(ioDispatcher) {
        val file = folder.recordingAt(File(folder.directory, name))
        when {
            file == null -> SaveResult.Failed
            file.name == folder.recording -> SaveResult.InProgress
            else -> file.copyTo(target)
        }
    }

    /** A hidden file in the folder, never a recording's name; `null` when it cannot be created. */
    private fun newTemporary(): File? = try {
        folder.directory.mkdirs()
        File.createTempFile(TEMPORARY_PREFIX, TEMPORARY_SUFFIX, folder.directory)
    } catch (e: IOException) {
        Log.w(TAG, "Could not start an import", e)
        null
    }

    private suspend fun admit(source: ImportSource, temporary: File): ImportResult =
        when (source.copyInto(temporary, rule)) {
            ImportCopy.COPIED -> stored(temporary, source.displayName)
            ImportCopy.TOO_LARGE -> ImportResult.TooLarge(rule.maxBytes)
            ImportCopy.FAILED -> ImportResult.Failed
        }

    /** The copy in [temporary], scanned and, if it holds a valid frame, admitted among the recordings. */
    private fun stored(temporary: File, displayName: String?): ImportResult = try {
        val scan = temporary.inputStream().use(RecordingScan::of)
        val size = temporary.length()
        val modified = temporary.lastModified()
        if (scan.hasValidFrame) {
            folder.admit(temporary, displayName, rule).also { result ->
                if (result is ImportResult.Imported) {
                    summaries.remember(result.name, size, modified, scan)
                    changed()
                }
            }
        } else {
            ImportResult.NotATlog
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not store an import", e)
        ImportResult.Failed
    }

    /** What an import cut short by a crash or a kill left behind; only imports write these, one at a time. */
    private fun deleteLeftovers() {
        folder.directory.listFiles { file ->
            file.name.startsWith(TEMPORARY_PREFIX) && file.name.endsWith(TEMPORARY_SUFFIX)
        }?.forEach(File::delete)
    }

    private fun changed() = changes.update { it + 1 }

    private companion object {
        const val TAG = "RecordingRepository"
        const val TEMPORARY_PREFIX = ".import-"
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
