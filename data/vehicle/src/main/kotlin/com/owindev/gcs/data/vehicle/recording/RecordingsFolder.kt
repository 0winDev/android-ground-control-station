package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.Recording
import com.owindev.gcs.core.domain.recording.RecordingRule
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.StandardCopyOption

/** The folder of the flight logs in the app's private files (`docs/security.md`, S-5). */
internal const val RECORDINGS_FOLDER = "tlogs"

/**
 * The folder of the flight logs, [directory], shared by the recorder, the replay and the recordings list: which files
 * are the GCS's recordings, and the limit applied to them (REQ-039). Whatever changes the folder (a recording opening
 * or closing, a delete, an import) runs under one lock, so retention never races with it.
 */
internal class RecordingsFolder(val directory: File, private val rule: RecordingRule) {

    private val lock = Any()

    /** Names the last log closed across restarts; retention never deletes it. */
    val marker = LastClosedMarker(directory)

    /**
     * The name of the log being replayed, which retention never deletes (REQ-037), `null` once no replay reads it. Set
     * before the replay closes the live session, whose closing applies the limit.
     */
    @Volatile
    var replayed: String? = null

    /** The name of the log being recorded, set by the recorder under [locked]; never deleted. */
    @Volatile
    var recording: String? = null

    /**
     * The last log the recorder closed since the app started, set under [locked] before the [marker] is written, so a
     * marker that fails to write never leaves it unprotected.
     */
    @Volatile
    var lastClosed: String? = null

    fun <T> locked(action: () -> T): T = synchronized(lock, action)

    /**
     * Deletes the oldest logs until all fit in [RecordingRule.maxTotalBytes], never the last one closed, the one
     * [replayed] nor the one [recording] (REQ-039), and returns the names deleted.
     */
    fun deleteOldest(): List<String> = locked {
        val logs = logs()
        removed(rule.toDelete(logs, keptAmong(logs), replayed, recording))
    }

    fun recordingFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.matches(LastClosedMarker.LOG_NAME) }.orEmpty().toList()

    /** Deletes the recording [name] unless it is being recorded or replayed (REQ-038). */
    fun delete(name: String): DeleteResult = locked {
        val file = recordingAt(File(directory, name))
        when (file?.name) {
            null -> DeleteResult.NotFound

            recording -> DeleteResult.InProgress

            replayed -> DeleteResult.Replaying

            else -> if (file.delete()) {
                Log.i(TAG, "Deleted a recording at the operator's request")
                DeleteResult.Deleted
            } else {
                DeleteResult.Failed
            }
        }
    }

    /**
     * Moves the copy of an import, [temporary] in this folder, in among the recordings under a name of its own from
     * [displayName] (REQ-038), and applies the limit as a new recording does: the oldest logs go, never the protected
     * ones. When the limit would take the import itself, nothing is moved nor deleted and it is [ImportResult.NoRoom];
     * while a flight is recorded, it is [ImportResult.Recording].
     * The caller deletes [temporary] whatever the result.
     */
    @Throws(IOException::class)
    fun admit(temporary: File, displayName: String?, names: ImportRule): ImportResult = locked {
        if (recording != null) return@locked ImportResult.Recording
        val name = names.nameFor(displayName, taken = directory.list().orEmpty().toSet())
        val incoming = Recording(name, temporary.length(), temporary.lastModified())
        val logs = logs()
        // Named before the import joins, or the rule would take the import, the newest file, for the last one closed.
        val doomed = rule.toDelete(logs + incoming, keptAmong(logs), replayed, recording)
        if (doomed.any { it.name == name }) return@locked ImportResult.NoRoom
        Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        Log.i(TAG, "Imported a recording")
        ImportResult.Imported(name, deleted = removed(doomed))
    }

    /**
     * [file] by its real path if it is one of the GCS's recordings: a regular file named as the recorder names its logs
     * (`.tlog`, never the marker) right in [directory], by real paths, links resolved, which a canonical path does not
     * do on every platform, so neither `..` nor a link can reach another file. `null` for anything else, a file that
     * does not exist included. Callers open the real path it returns, never the name they were given.
     */
    fun recordingAt(file: File): File? = try {
        val real = file.toPath().toRealPath()
        val isRecording = real.parent == directory.toPath().toRealPath() &&
            Files.isRegularFile(real) &&
            real.fileName.toString().matches(LastClosedMarker.LOG_NAME)
        real.toFile().takeIf { isRecording }
    } catch (@Suppress("SwallowedException") unresolvable: IOException) {
        null
    } catch (@Suppress("SwallowedException") invalid: InvalidPathException) {
        null
    }

    private fun logs(): List<Recording> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }.orEmpty()
            .map { Recording(name = it.name, sizeBytes = it.length(), lastModifiedMillis = it.lastModified()) }

    /** The last one closed: this run's, else the [marker]'s, else the newest of [logs], as before the app started. */
    private fun keptAmong(logs: List<Recording>): String? = lastClosed?.takeIf { File(directory, it).isFile }
        ?: marker.read()
        ?: logs.maxWithOrNull(compareBy({ it.lastModifiedMillis }, { it.name }))?.name

    private fun removed(recordings: List<Recording>): List<String> = recordings.mapNotNull { recording ->
        if (File(directory, recording.name).delete()) {
            Log.i(TAG, "Deleted a recording to stay within the recordings limit")
            recording.name
        } else {
            Log.w(TAG, "Could not delete a recording to stay within the limit")
            null
        }
    }

    private companion object {
        const val TAG = "RecordingsFolder"
        const val EXTENSION = ".tlog"
    }
}
