package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.Recording
import com.owindev.gcs.core.domain.recording.RecordingRule
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException

/** The folder of the flight logs in the app's private files (`docs/security.md`, S-5). */
internal const val RECORDINGS_FOLDER = "tlogs"

/**
 * The folder of the flight logs, [directory], shared by the recorder, the replay and the recordings list: which files
 * are the GCS's recordings, and the limit applied to them (REQ-039). Retention runs under one lock, so whatever else
 * changes the folder under it never races with it.
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

    /**
     * Deletes the oldest logs until all fit in [RecordingRule.maxTotalBytes], never [kept] nor the one [replayed]
     * (REQ-039).
     */
    fun deleteOldest(kept: String?) = synchronized(lock) {
        val recordings = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }.orEmpty()
            .map { Recording(name = it.name, sizeBytes = it.length(), lastModifiedMillis = it.lastModified()) }
        rule.toDelete(recordings, kept, replayed).forEach { recording ->
            if (File(directory, recording.name).delete()) {
                Log.i(TAG, "Deleted ${recording.name} to stay within the recordings limit")
            } else {
                Log.w(TAG, "Could not delete ${recording.name}")
            }
        }
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

    private companion object {
        const val TAG = "RecordingsFolder"
        const val EXTENSION = ".tlog"
    }
}
