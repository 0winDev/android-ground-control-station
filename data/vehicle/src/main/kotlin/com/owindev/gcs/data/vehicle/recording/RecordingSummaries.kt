package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.RecordingSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The recordings in the [folder] as the list shows them (REQ-038). Each log's span is cached by name, size and time of
 * last write, so a log is scanned once while unchanged; an unreadable one is listed with no span rather than not at
 * all.
 */
internal class RecordingSummaries(private val folder: RecordingsFolder) {

    private val scans = ConcurrentHashMap<String, CachedScan>()

    /** Every recording, newest first by its first valid frame, by last write when it has none. */
    fun all(): List<RecordingSummary> {
        val recording = folder.recording
        val replayed = folder.replayed
        val files = folder.recordingFiles()
        scans.keys.retainAll(files.mapTo(HashSet(), File::getName))
        return files
            .map { file -> file to summaryOf(file, recording, replayed) }
            .sortedWith(
                compareByDescending<Pair<File, RecordingSummary>> { (file, summary) ->
                    summary.startMillis ?: file.lastModified()
                }.thenBy { (file, _) -> file.name },
            )
            .map { it.second }
    }

    private fun summaryOf(file: File, recording: String?, replayed: String?): RecordingSummary {
        val size = file.length()
        val scan = scanOf(file, size)
        // Stamps of 2⁶³ µs or more read as negative (TlogRecord): no date, no span.
        val first = scan?.firstMicros?.takeIf { it >= 0 }
        val last = scan?.lastMicros?.takeIf { it >= 0 }
        return RecordingSummary(
            name = file.name,
            startMillis = first?.div(MICROS_PER_MILLI),
            duration = if (first != null && last != null) spanOf(first, last) else null,
            sizeBytes = size,
            inProgress = file.name == recording,
            replaying = file.name == replayed,
        )
    }

    /** `null` when the file cannot be read: listed with no span rather than not at all. */
    private fun scanOf(file: File, size: Long): RecordingScan? {
        val modified = file.lastModified()
        scans[file.name]?.takeIf { it.size == size && it.modified == modified }?.let { return it.scan }
        return try {
            file.inputStream().use(RecordingScan::of).also { scans[file.name] = CachedScan(size, modified, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read ${file.name}", e)
            null
        }
    }

    /** Caches the [scan] of a log just written as [name], as it is now on disk. */
    fun remember(name: String, size: Long, modified: Long, scan: RecordingScan) {
        scans[name] = CachedScan(size, modified, scan)
    }

    private class CachedScan(val size: Long, val modified: Long, val scan: RecordingScan)

    private companion object {
        const val TAG = "RecordingSummaries"
        const val MICROS_PER_MILLI = 1_000L

        /** A wall clock set back between two records gives no negative span. */
        fun spanOf(first: Long, last: Long): Duration =
            if (last >= first) (last - first).microseconds else Duration.ZERO
    }
}
