package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.Recording
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.mavlink.LogFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.mavlink.TlogWriter
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Records not yet written beyond which new ones are dropped and counted: a few seconds of a busy link. */
internal const val MAX_PENDING_RECORDS = 1024

/** The folder of the flight logs in the app's private files (`docs/security.md`, S-5). */
internal const val RECORDINGS_FOLDER = "tlogs"

/**
 * Records each flight to a .tlog in [directory] (REQ-036): every frame received and sent while a recording is open,
 * each stamped on [clock] when it is handed over, signatures stripped and SETUP_SIGNING left out (ADR 0006).
 *
 * A recording opens with the first HEARTBEAT of the [activeVehicle] (REQ-020), as `yyyy-MM-dd_HH-mm-ss.tlog` in
 * [clock]'s zone. It stays open through link loss, so the gap stays in the log, and closes when the session stops or
 * after [RecordingRule.closeAfterLinkLoss] without a HEARTBEAT from that vehicle, measured on [timeSource]; its next
 * HEARTBEAT then opens a new one. A closed log is synced to the disk.
 *
 * The limit is applied when a recording opens and when it closes (REQ-039): the oldest logs in [directory] are deleted
 * until all fit in [RecordingRule.maxTotalBytes], never the one being recorded nor the last one closed, named by the
 * [LastClosedMarker] across restarts (the newest on disk when it is missing or damaged). A log a disk failure cut short
 * counts as closed. While open, a recording grows past the limit unchecked: about 1.1 kB/s from ArduCopter SITL, so
 * 256 MiB in some 65 hours.
 *
 * Only the live link feeds it: a replay is never recorded. The callers never wait: frames queue for one writer in
 * [scope] (on a device, on `Dispatchers.IO`), and beyond [MAX_PENDING_RECORDS] they are dropped and counted. A disk
 * failure stops the recording, is logged once, shows in [status] and never reaches the link; the next session tries
 * again.
 */
// One collaborator per concern above; grouping them would only hide that.
@Suppress("LongParameterList")
internal class FlightRecorder(
    private val directory: File,
    private val rule: RecordingRule,
    private val activeVehicle: ActiveVehicle,
    private val timeSource: TimeSource.WithComparableMarks,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val openOutput: (File) -> OutputStream = ::FileOutputStream,
    private val syncOutput: (OutputStream) -> Unit = ::syncToDisk,
) {

    // Records travel as their bare arrays and controls as objects, so a frame costs nothing beyond its record.
    private val events = Channel<Any>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    private val droppedCount = AtomicLong()

    /**
     * The name of the log being replayed, which retention never deletes (REQ-037), `null` once no replay reads it. Set
     * before the replay closes the live session, whose closing applies the limit.
     */
    @Volatile
    var replayed: String? = null
    private val signedUnknownCount = AtomicLong()
    private val setupSigningCount = AtomicLong()
    private val mutableStatus = MutableStateFlow(RecorderStatus.IDLE)
    private val marker = LastClosedMarker(directory)

    /**
     * The latest HEARTBEAT of the active vehicle not yet taken by the writer. One per session, replaced when it stops,
     * so a HEARTBEAT still queued from a stopped session never takes the next session's place.
     */
    @Volatile
    private var latestHeard = AtomicReference<HeardAt?>()

    private val writer: Job by lazy { scope.launch { write() } }

    /** Whether a log is being written now; [RecorderStatus.FAILED] from a disk failure until a log opens again. */
    val status: StateFlow<RecorderStatus> = mutableStatus.asStateFlow()

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
            // Marked on arrival, as the vehicle repository marks it, not when the writer gets to it.
            if (state.systemId == activeVehicle.liveSystemId) heard(HeardAt(atMillis, timeSource.markNow()))
        }
        offer(frame.bytes, micros(atMillis))
    }

    /** A frame this GCS has just sent on the link. */
    fun sent(frame: ByteArray) = offer(frame, micros(clock.millis()))

    /** The link session has stopped: the recording closes. Called from the receive loop, after its last frame. */
    fun sessionStopped() {
        latestHeard = AtomicReference()
        control(Control.SessionStopped)
    }

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

    // The writer needs the first HEARTBEAT's time, to name a log, and the latest one's mark, to close it: a slow disk
    // holds one event in the queue, not one per HEARTBEAT.
    private fun heard(heard: HeardAt) {
        val latest = latestHeard
        if (latest.getAndSet(heard) == null) control(Control.Heard(heard.atMillis, latest))
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
                    val latest = event.latest.getAndSet(null) ?: continue
                    state.lastHeartbeat = latest.mark
                    state.openIfIdle(event.firstAtMillis)
                    closeAfter(rule.closeAfterLinkLoss - latest.mark.elapsedNow())
                }

                Control.TimedOut -> {
                    val since = state.lastHeartbeat?.elapsedNow() ?: Duration.INFINITE
                    if (rule.closesAfter(since)) state.close() else closeAfter(rule.closeAfterLinkLoss - since)
                }

                Control.SessionStopped -> {
                    timer?.cancel()
                    state.close()
                    state.blocked = false
                }
            }
            // Written through once the queue holds no more records, so a crash loses at most what is queued.
            if (pending.get() == 0) state.flush()
            mutableStatus.value = state.status
        }
    }

    /** The writer's state; only the writer coroutine touches it. */
    private inner class WriterState {
        var open: OpenRecording? = null
        var lastHeartbeat: ComparableTimeMark? = null
        var status = RecorderStatus.IDLE

        /** After a disk failure, until the session stops: no log opens. */
        var blocked = false

        /** Unknown after the app starts, when the [marker] stands for it. */
        var lastClosed: String? = null

        fun openIfIdle(atMillis: Long) {
            if (open != null || blocked) return
            guarded {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
                val file = newFile(atMillis)
                deleteOldest(kept = lastClosed ?: marker.read())
                val output = openOutput(file)
                open = OpenRecording(file, output, BufferedOutputStream(output))
                status = RecorderStatus.RECORDING
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
            guarded {
                recording.stream.flush()
                // On the disk before it counts as closed: a power cut must not lose a finished flight.
                syncOutput(recording.output)
                recording.stream.close()
                open = null
                status = RecorderStatus.IDLE
                Log.i(TAG, "Recording ${recording.file.name} closed; left out so far: $stats")
                closed(recording.file.name)
                deleteOldest(kept = recording.file.name)
            }
        }

        private fun closed(name: String) {
            lastClosed = name
            marker.write(name)
        }

        private inline fun guarded(action: () -> Unit) {
            try {
                action()
            } catch (e: IOException) {
                abandon()
                blocked = true
                status = RecorderStatus.FAILED
                Log.w(TAG, "The flight log failed; recording stopped until the next session", e)
            }
        }

        /** What a failure cut short is still this flight's log: the last one closed, never the first to go. */
        private fun abandon() {
            val recording = open ?: return
            open = null
            runCatchingIo { recording.stream.close() }
            if (recording.file.isFile) {
                lastClosed = recording.file.name
                runCatchingIo { marker.write(recording.file.name) }
            }
        }
    }

    private fun newFile(atMillis: Long): File {
        val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), clock.zone).format(FILE_NAME_FORMAT)
        return generateSequence(0) { it + 1 }
            .map { clash -> File(directory, if (clash == 0) "$stamp$EXTENSION" else "${stamp}_$clash$EXTENSION") }
            .first { !it.exists() }
    }

    private fun deleteOldest(kept: String?) {
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

    private class OpenRecording(val file: File, val output: OutputStream, val stream: OutputStream)

    /** A HEARTBEAT of the active vehicle, with when it arrived on both clocks. */
    private class HeardAt(val atMillis: Long, val mark: ComparableTimeMark)

    private sealed interface Control {
        /** The active vehicle was heard, first at [firstAtMillis]; [latest] holds its latest HEARTBEAT. */
        class Heard(val firstAtMillis: Long, val latest: AtomicReference<HeardAt?>) : Control

        data object TimedOut : Control

        data object SessionStopped : Control
    }

    private companion object {
        const val TAG = "FlightRecorder"
        const val EXTENSION = ".tlog"
        const val MICROS_PER_MILLI = 1_000L
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        // A .tlog cannot hold a time before the Unix epoch, and a clock set that far back must not stop the link.
        fun micros(atMillis: Long): Long = atMillis.coerceAtLeast(0) * MICROS_PER_MILLI

        fun syncToDisk(output: OutputStream) {
            (output as? FileOutputStream)?.fd?.sync()
        }

        inline fun runCatchingIo(action: () -> Unit) {
            try {
                action()
            } catch (_: IOException) {
                // Already failing: the first failure is the one logged.
            }
        }
    }
}

/** Whether the recorder is writing a log, waiting for the active vehicle, or stopped by a disk failure. */
internal enum class RecorderStatus { IDLE, RECORDING, FAILED }

/** Frames left out of the logs: [dropped] behind a slow disk, and the two kinds ADR 0006 keeps out. */
internal data class RecorderStats(val dropped: Long, val signedUnknownMessage: Long, val setupSigning: Long)
