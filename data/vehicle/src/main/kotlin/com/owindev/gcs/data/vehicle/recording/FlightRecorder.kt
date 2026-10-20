package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.Recording
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.mavlink.LogFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.mavlink.TlogWriter
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.state.HeartbeatMapper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Records not yet written beyond which new ones are dropped and counted: a few seconds of a busy link. */
internal const val MAX_PENDING_RECORDS = 1024

/** The folder of the flight logs in the app's private files (`docs/security.md`, S-5). */
internal const val RECORDINGS_FOLDER = "tlogs"

/**
 * Records each flight to a .tlog in [directory] (REQ-036): every frame received and sent while a recording is open,
 * each stamped on [clock] when it is handed over, signatures stripped and SETUP_SIGNING left out (ADR 0006).
 *
 * A recording opens with the first HEARTBEAT of the active vehicle (the first autopilot heard, REQ-020), as
 * `yyyy-MM-dd_HH-mm-ss.tlog` in [clock]'s zone. It stays open through link loss, so the gap stays in the log, and
 * closes when the session stops or after [RecordingRule.closeAfterLinkLoss] without a HEARTBEAT from that vehicle,
 * measured on [timeSource]; its next HEARTBEAT then opens a new one. When a recording opens and when it closes, the
 * oldest logs in [directory] are deleted until all fit in [RecordingRule.maxTotalBytes] (REQ-039), never the newest.
 *
 * Only the live link feeds it: a replay is never recorded. The callers never wait: frames queue for one writer in
 * [scope] (on a device, on `Dispatchers.IO`), and beyond [MAX_PENDING_RECORDS] they are dropped and counted. A disk
 * failure stops the recording, is logged once and never reaches the link; the next session tries again.
 */
// One collaborator per concern above; grouping them would only hide that.
@Suppress("LongParameterList")
internal class FlightRecorder(
    private val directory: File,
    private val rule: RecordingRule,
    private val timeSource: TimeSource.WithComparableMarks,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val openOutput: (File) -> OutputStream = ::FileOutputStream,
) {

    // Records travel as their bare arrays and controls as objects, so a frame costs nothing beyond its record.
    private val events = Channel<Any>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    private val droppedCount = AtomicLong()
    private val signedUnknownCount = AtomicLong()
    private val setupSigningCount = AtomicLong()

    /** Pinned by the first autopilot HEARTBEAT, as the vehicle repository pins it; written only by [received]. */
    @Volatile
    private var activeSystemId: Int? = null

    private val writer: Job by lazy { scope.launch { write() } }

    /** What has been left out of the logs since the app started. */
    val stats: RecorderStats
        get() = RecorderStats(
            dropped = droppedCount.get(),
            signedUnknownMessage = signedUnknownCount.get(),
            setupSigning = setupSigningCount.get(),
        )

    /** A frame from the link, with its decoded [message] when it has one; called from the receive loop. */
    fun received(frame: RawFrame, message: ReceivedMessage?) {
        val atMillis = clock.millis()
        message?.let(HeartbeatMapper::vehicleStateOf)?.let { state ->
            val systemId = state.systemId.value
            val active = activeSystemId ?: systemId.also { activeSystemId = it }
            // Marked on arrival, as the vehicle repository marks it, not when the writer gets to it.
            if (systemId == active) control(Control.Heard(atMillis, timeSource.markNow()))
        }
        offer(frame.bytes, atMillis * MICROS_PER_MILLI)
    }

    /** A frame this GCS has just sent on the link. */
    fun sent(frame: ByteArray) = offer(frame, clock.millis() * MICROS_PER_MILLI)

    /** The link session has stopped: the recording closes. */
    fun sessionStopped() = control(Control.SessionStopped)

    private fun offer(frame: ByteArray, atMicros: Long) {
        when (val loggable = TlogWriter.loggable(frame, MavlinkMessages)) {
            is LogFrame.Write -> enqueue(TlogWriter.record(atMicros, loggable.bytes))

            LogFrame.SignedUnknownMessage -> signedUnknownCount.incrementAndGet()

            LogFrame.SetupSigning -> setupSigningCount.incrementAndGet()

            // Neither the parser nor the encoder hands out MAVLink 1.
            LogFrame.Mavlink1 -> error("A MAVLink 1 frame reached the recorder")
        }
    }

    private fun enqueue(record: ByteArray) {
        if (pending.incrementAndGet() > MAX_PENDING_RECORDS) {
            pending.decrementAndGet()
            if (droppedCount.getAndIncrement() == 0L) Log.w(TAG, "The flight log fell behind the link; dropping frames")
            return
        }
        writer.start()
        events.trySend(record)
    }

    private fun control(control: Control) {
        writer.start()
        events.trySend(control)
    }

    private suspend fun write() = coroutineScope {
        val state = WriterState()
        var timer: Job? = null
        fun closeAfter(after: Duration) {
            timer?.cancel()
            timer = launch {
                delay(after)
                events.send(Control.TimedOut)
            }
        }
        for (event in events) {
            when (event) {
                is ByteArray -> {
                    pending.decrementAndGet()
                    state.write(event)
                }

                is Control.Heard -> {
                    state.lastHeartbeat = event.mark
                    state.openIfIdle(event.atMillis)
                    closeAfter(rule.closeAfterLinkLoss - event.mark.elapsedNow())
                }

                Control.TimedOut -> {
                    val since = state.lastHeartbeat?.elapsedNow() ?: Duration.INFINITE
                    if (rule.closesAfter(since)) state.close() else closeAfter(rule.closeAfterLinkLoss - since)
                }

                Control.SessionStopped -> {
                    timer?.cancel()
                    state.close()
                    state.failed = false
                }
            }
            // Written through once the queue holds no more records, so a crash loses at most what is queued.
            if (pending.get() == 0) state.flush()
        }
    }

    /** The writer's state; only the writer coroutine touches it. */
    private inner class WriterState {
        var open: OpenRecording? = null
        var failed = false
        var lastHeartbeat: ComparableTimeMark? = null

        fun openIfIdle(atMillis: Long) {
            if (open != null || failed) return
            guarded {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
                val file = newFile(atMillis)
                deleteOldest(current = file.name)
                open = OpenRecording(file, BufferedOutputStream(openOutput(file)))
                Log.i(TAG, "Recording the flight to ${file.name}")
            }
        }

        fun write(record: ByteArray) {
            val recording = open ?: return
            guarded { recording.stream.write(record) }
        }

        fun flush() {
            val recording = open ?: return
            guarded { recording.stream.flush() }
        }

        fun close() {
            val recording = open ?: return
            open = null
            guarded {
                recording.stream.close()
                Log.i(TAG, "Recording ${recording.file.name} closed; left out so far: $stats")
                deleteOldest(current = recording.file.name)
            }
        }

        private inline fun guarded(action: () -> Unit) {
            try {
                action()
            } catch (e: IOException) {
                open?.let { runCatchingIo { it.stream.close() } }
                open = null
                failed = true
                Log.w(TAG, "The flight log failed; recording stopped until the next session", e)
            }
        }
    }

    private fun newFile(atMillis: Long): File {
        val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), clock.zone).format(FILE_NAME_FORMAT)
        return generateSequence(0) { it + 1 }
            .map { clash -> File(directory, if (clash == 0) "$stamp$EXTENSION" else "${stamp}_$clash$EXTENSION") }
            .first { !it.exists() }
    }

    private fun deleteOldest(current: String) {
        val recordings = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }.orEmpty()
            .map { Recording(name = it.name, sizeBytes = it.length(), lastModifiedMillis = it.lastModified()) }
        rule.toDelete(recordings, current).forEach { recording ->
            if (File(directory, recording.name).delete()) {
                Log.i(TAG, "Deleted ${recording.name} to stay within the recordings limit")
            } else {
                Log.w(TAG, "Could not delete ${recording.name}")
            }
        }
    }

    private class OpenRecording(val file: File, val stream: OutputStream)

    private sealed interface Control {
        /** A HEARTBEAT of the active vehicle, with when it arrived on both clocks. */
        class Heard(val atMillis: Long, val mark: ComparableTimeMark) : Control

        data object TimedOut : Control

        data object SessionStopped : Control
    }

    private companion object {
        const val TAG = "FlightRecorder"
        const val EXTENSION = ".tlog"
        const val MICROS_PER_MILLI = 1_000L
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        inline fun runCatchingIo(action: () -> Unit) {
            try {
                action()
            } catch (_: IOException) {
                // Already failing: the first failure is the one logged.
            }
        }
    }
}

/** Frames left out of the logs: [dropped] behind a slow disk, and the two kinds ADR 0006 keeps out. */
internal data class RecorderStats(val dropped: Long, val signedUnknownMessage: Long, val setupSigning: Long)
