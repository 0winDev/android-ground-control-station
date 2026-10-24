package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.mavlink.TlogReader
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.HeartbeatMapper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The real codec and files in a temporary folder; time is the test scheduler's, the wall clock a settable one. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightRecorderTest {

    @TempDir
    lateinit var temp: File

    private val testScope = TestScope()
    private val clock = SettableClock(START)
    private val activeVehicle = ActiveVehicle()
    private val openStreams = mutableListOf<OutputStream>()
    private val directory get() = File(temp, RECORDINGS_FOLDER)

    private lateinit var recorder: FlightRecorder

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        recorder = recorder()
    }

    @AfterEach
    fun tearDown() {
        // Windows cannot delete the temporary folder while a test leaves a recording open.
        openStreams.forEach(OutputStream::close)
        unmockkStatic(Log::class)
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN no HEARTBEAT yet WHEN frames arrive THEN nothing is recorded`() = testScope.runTest {
        receive(SCALED_PRESSURE)

        runCurrent()

        logs().shouldBeEmpty()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the vehicle's first HEARTBEAT WHEN recording THEN a log named by the wall clock starts with it`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            receive(SCALED_PRESSURE)

            stopAndWrite()

            logs() shouldBeEqualTo listOf(START_NAME)
            frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE), fixture(SCALED_PRESSURE))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN only a ground station's HEARTBEAT WHEN it arrives THEN nothing is recorded`() = testScope.runTest {
        receive(MISSION_PLANNER)

        runCurrent()

        logs().shouldBeEmpty()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN every kind of frame WHEN recorded THEN valid, unknown, unsigned and sent frames are written in order`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            receive(SCALED_PRESSURE)
            receive(HEARTBEAT_SIGNED)
            recorder.sent(fixture(DISARMED_LOITER).toByteArray())

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(
                fixture(DISARMED_STABILIZE),
                fixture(SCALED_PRESSURE),
                fixture(DISARMED_STABILIZE),
                fixture(DISARMED_LOITER),
            )
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed unknown ID and a SETUP_SIGNING WHEN recorded THEN they are left out and counted`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            receive(SCALED_PRESSURE_SIGNED)
            recorder.sent(fixture(SETUP_SIGNING).toByteArray())

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE))
            recorder.stats shouldBeEqualTo RecorderStats(dropped = 0, signedUnknownMessage = 1, setupSigning = 1)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN frames stamped before the writer runs WHEN written THEN each keeps the time it was handed over`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            clock.advance(LATER)
            receive(SCALED_PRESSURE)
            clock.advance(LATER)

            stopAndWrite()

            timestamps(START_NAME) shouldBeEqualTo listOf(START_MICROS, START_MICROS + LATER_MICROS)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed frame sent WHEN recorded THEN it is written without its signature`() = testScope.runTest {
        receive(DISARMED_STABILIZE)
        recorder.sent(fixture(HEARTBEAT_SIGNED).toByteArray())

        stopAndWrite()

        frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE), fixture(DISARMED_STABILIZE))
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a wall clock before 1970 WHEN frames are recorded THEN they are stamped at the epoch`() =
        testScope.runTest {
            clock.set(BEFORE_EPOCH)
            receive(DISARMED_STABILIZE)

            stopAndWrite()

            timestamps(BEFORE_EPOCH_NAME) shouldBeEqualTo listOf(0L)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN HEARTBEATs queued before the writer runs WHEN the log opens THEN it is named by the first`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            clock.advance(LATER)
            receive(DISARMED_LOITER)

            stopAndWrite()

            logs() shouldBeEqualTo listOf(START_NAME)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a HEARTBEAT still queued when the session stops WHEN the next one hears the vehicle THEN it logs it`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            recorder.sessionStopped()
            clock.advance(LATER)
            receive(DISARMED_LOITER)

            stopAndWrite()

            logs() shouldBeEqualTo listOf(START_NAME, LATER_NAME)
            frames(LATER_NAME) shouldBeEqualTo listOf(fixture(DISARMED_LOITER))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN HEARTBEATs queued before the writer runs WHEN timing the close THEN it counts from the latest`() =
        testScope.runTest {
            val heardAt = TestTimeSource()
            val queued = recorder(timeSource = heardAt)
            receive(DISARMED_STABILIZE, queued)
            heardAt += NINE_MINUTES
            receive(DISARMED_LOITER, queued)
            runCurrent()
            heardAt += LATER
            advanceTimeBy(LATER)
            receive(SCALED_PRESSURE, queued)

            queued.sessionStopped()
            runCurrent()

            frames(START_NAME) shouldBeEqualTo listOf(
                fixture(DISARMED_STABILIZE),
                fixture(DISARMED_LOITER),
                fixture(SCALED_PRESSURE),
            )
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the vehicle heard WHEN the log opens THEN the status is RECORDING`() = testScope.runTest {
        receive(DISARMED_STABILIZE)

        runCurrent()

        recorder.status.value shouldBeEqualTo RecorderStatus.RECORDING
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a recording WHEN the session stops THEN the status is IDLE`() = testScope.runTest {
        receive(DISARMED_STABILIZE)

        stopAndWrite()

        recorder.status.value shouldBeEqualTo RecorderStatus.IDLE
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN just under 10 minutes without HEARTBEAT WHEN a frame arrives THEN it joins the same log`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(10.minutes - 1.milliseconds)
            runCurrent()
            receive(SCALED_PRESSURE)

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE), fixture(SCALED_PRESSURE))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN exactly 10 minutes without HEARTBEAT WHEN a frame arrives THEN the log is closed without it`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(10.minutes)
            runCurrent()
            receive(SCALED_PRESSURE)

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN HEARTBEATs within 10 minutes of each other WHEN 18 minutes pass THEN the log stays open`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(NINE_MINUTES)
            receive(DISARMED_LOITER)
            advanceTimeBy(NINE_MINUTES)
            runCurrent()
            receive(SCALED_PRESSURE)

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(
                fixture(DISARMED_STABILIZE),
                fixture(DISARMED_LOITER),
                fixture(SCALED_PRESSURE),
            )
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a log closed after 10 minutes WHEN the vehicle's HEARTBEAT returns THEN a new log starts`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(10.minutes)
            runCurrent()
            clock.advance(10.minutes)
            receive(DISARMED_LOITER)

            stopAndWrite()

            logs() shouldBeEqualTo listOf(START_NAME, TEN_MINUTES_LATER_NAME)
            frames(TEN_MINUTES_LATER_NAME) shouldBeEqualTo listOf(fixture(DISARMED_LOITER))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a log closed after 10 minutes WHEN another vehicle's HEARTBEAT arrives THEN no log starts`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(10.minutes)
            runCurrent()
            clock.advance(10.minutes)
            receive(SECOND_VEHICLE)

            stopAndWrite()

            logs() shouldBeEqualTo listOf(START_NAME)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN another vehicle's HEARTBEAT WHEN the active one is silent THEN it does not keep the log open`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            advanceTimeBy(NINE_MINUTES)
            receive(SECOND_VEHICLE)
            advanceTimeBy(NINE_MINUTES)
            runCurrent()
            receive(SCALED_PRESSURE)

            stopAndWrite()

            frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_STABILIZE), fixture(SECOND_VEHICLE))
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a stopped session WHEN the next one hears the vehicle THEN a new log starts`() = testScope.runTest {
        receive(DISARMED_STABILIZE)
        recorder.sessionStopped()
        runCurrent()
        clock.advance(LATER)
        receive(DISARMED_LOITER)

        stopAndWrite()

        logs() shouldBeEqualTo listOf(START_NAME, LATER_NAME)
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN two logs started in the same second WHEN naming the second THEN it gets a suffix`() = testScope.runTest {
        receive(DISARMED_STABILIZE)
        recorder.sessionStopped()
        runCurrent()
        receive(DISARMED_LOITER)

        stopAndWrite()

        logs() shouldBeEqualTo listOf(START_NAME, START_SUFFIXED_NAME)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN old logs over the limit WHEN a recording opens THEN the oldest go and the rest fit`() =
        testScope.runTest {
            val small = recorder(RecordingRule(maxTotalBytes = LIMIT_BYTES))
            oldLog(OLDEST_NAME, LIMIT_BYTES, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, LIMIT_BYTES / 2, lastModifiedMillis = 2)
            receive(DISARMED_STABILIZE, small)

            small.sessionStopped()
            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME, IMPORTED_NAME)
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN the oldest log being replayed WHEN the live recording closes over the limit THEN it is kept`() =
        testScope.runTest {
            val rule = RecordingRule(maxTotalBytes = LIMIT_BYTES)
            val folder = RecordingsFolder(directory, rule)
            val small = recorder(rule, folder)
            receive(DISARMED_STABILIZE, small)
            runCurrent()
            oldLog(OLDEST_NAME, LIMIT_BYTES, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, LIMIT_BYTES / 2, lastModifiedMillis = 2)
            folder.replayed = OLDEST_NAME

            small.sessionStopped()
            runCurrent()

            logs() shouldBeEqualTo listOf(OLDEST_NAME, START_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a recording over the limit by itself WHEN it closes THEN every other log goes but never it`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            receive(DISARMED_STABILIZE, tiny)
            runCurrent()
            oldLog(OLDEST_NAME, 1, lastModifiedMillis = 1)
            tiny.sessionStopped()

            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a closed log over the limit by itself WHEN the next recording opens THEN the closed one is kept`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            receive(DISARMED_STABILIZE, tiny)
            tiny.sessionStopped()
            runCurrent()
            clock.advance(LATER)
            receive(DISARMED_LOITER, tiny)

            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME, LATER_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN logs from before the app started over the limit WHEN a recording opens THEN the newest is kept`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            oldLog(OLDEST_NAME, 1, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, 1, lastModifiedMillis = 2)
            receive(DISARMED_STABILIZE, tiny)

            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME, IMPORTED_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a log a full disk cut short WHEN the next recording opens THEN it is kept as the last one closed`() =
        testScope.runTest {
            var opens = 0
            val filling = recorder(
                rule = RecordingRule(maxTotalBytes = 1),
                openOutput = { if (opens++ == 1) FullAfterFirstWrite(it) else opened(it) },
            )
            receive(DISARMED_STABILIZE, filling)
            filling.sessionStopped()
            runCurrent()
            clock.advance(LATER)
            receive(DISARMED_LOITER, filling)
            runCurrent()
            receive(SCALED_PRESSURE, filling)
            filling.sessionStopped()
            runCurrent()
            clock.advance(LATER)
            receive(DISARMED_STABILIZE, filling)

            runCurrent()

            logs() shouldBeEqualTo listOf(LATER_NAME, FOUR_MINUTES_LATER_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a closed log WHEN it closes THEN the marker names it and no temporary file is left`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)

            stopAndWrite()

            LastClosedMarker(directory).read() shouldBeEqualTo START_NAME
            File(directory, LastClosedMarker.TEMPORARY_NAME).exists() shouldBeEqualTo false
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN the marker names an older log WHEN a recording opens after a restart THEN that one is kept`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            oldLog(OLDEST_NAME, 1, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, 1, lastModifiedMillis = 2)
            LastClosedMarker(directory).write(OLDEST_NAME)
            receive(DISARMED_STABILIZE, tiny)

            runCurrent()

            logs() shouldBeEqualTo listOf(OLDEST_NAME, START_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a damaged marker WHEN a recording opens after a restart THEN the newest on disk is kept`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            oldLog(OLDEST_NAME, 1, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, 1, lastModifiedMillis = 2)
            File(directory, LastClosedMarker.FILE_NAME).writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x2F))
            receive(DISARMED_STABILIZE, tiny)

            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME, IMPORTED_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN logs exactly at the limit and a marker WHEN a recording opens THEN the marker does not count`() =
        testScope.runTest {
            val small = recorder(RecordingRule(maxTotalBytes = LIMIT_BYTES))
            oldLog(OLDEST_NAME, LIMIT_BYTES, lastModifiedMillis = 1)
            oldLog(IMPORTED_NAME, 0, lastModifiedMillis = 2)
            LastClosedMarker(directory).write(IMPORTED_NAME)
            receive(DISARMED_STABILIZE, small)

            runCurrent()

            logs() shouldBeEqualTo listOf(OLDEST_NAME, START_NAME, IMPORTED_NAME)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN more frames than the queue holds WHEN the writer is behind THEN the surplus is dropped and counted`() =
        testScope.runTest {
            receive(DISARMED_STABILIZE)
            repeat(MAX_PENDING_RECORDS) { receive(SCALED_PRESSURE) }

            stopAndWrite()

            frames(START_NAME).size shouldBeEqualTo MAX_PENDING_RECORDS
            recorder.stats.dropped shouldBeEqualTo 1
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a disk that fails WHEN recording THEN it stops, is logged once and frames keep being taken`() =
        testScope.runTest {
            val failing = recorder(openOutput = { FailingStream() })
            receive(DISARMED_STABILIZE, failing)
            runCurrent()
            receive(DISARMED_LOITER, failing)

            runCurrent()

            verifyOnce {
                Log.w(TAG, any<String>(), any<IOException>())
            }
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a disk that failed WHEN frames keep arriving THEN they are taken and the status is FAILED`() =
        testScope.runTest {
            val failing = recorder(openOutput = { FailingStream() })
            receive(DISARMED_STABILIZE, failing)
            runCurrent()
            receive(DISARMED_LOITER, failing)

            runCurrent()

            failing.status.value shouldBeEqualTo RecorderStatus.FAILED
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a disk that failed WHEN the next session hears the vehicle THEN it records again`() = testScope.runTest {
        var failures = 1
        val recovering = recorder(openOutput = { if (failures-- > 0) FailingStream() else opened(it) })
        receive(DISARMED_STABILIZE, recovering)
        recovering.sessionStopped()
        runCurrent()
        receive(DISARMED_LOITER, recovering)

        recovering.sessionStopped()
        runCurrent()

        frames(START_NAME) shouldBeEqualTo listOf(fixture(DISARMED_LOITER))
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a recording WHEN it closes THEN it is synced to the disk before it is closed`() = testScope.runTest {
        val events = mutableListOf<String>()
        val syncing = recorder(
            openOutput = { ClosingStream(it, events) },
            syncOutput = { events += SYNCED },
        )
        receive(DISARMED_STABILIZE, syncing)
        runCurrent()

        syncing.sessionStopped()
        runCurrent()

        events shouldBeEqualTo listOf(SYNCED, CLOSED)
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a sync that fails WHEN the log closes THEN its file is closed and the status stays FAILED`() =
        testScope.runTest {
            val events = mutableListOf<String>()
            val failing = recorder(
                openOutput = { ClosingStream(it, events) },
                syncOutput = { throw IOException("I/O error") },
            )
            receive(DISARMED_STABILIZE, failing)
            runCurrent()

            failing.sessionStopped()
            runCurrent()

            events shouldBeEqualTo listOf(CLOSED)
            failing.status.value shouldBeEqualTo RecorderStatus.FAILED
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a folder that cannot be created WHEN the vehicle is heard THEN nothing is recorded and it is logged`() =
        testScope.runTest {
            directory.writeText("not a folder")
            receive(DISARMED_STABILIZE)

            runCurrent()

            directory.isFile shouldBeEqualTo true
            verifyOnce {
                Log.w(TAG, any<String>(), any<IOException>())
            }
        }

    @Tag("REQ-038")
    @Test
    fun `GIVEN the active vehicle heard WHEN its recording opens THEN the folder protects it as the one recorded`() =
        testScope.runTest {
            val folder = RecordingsFolder(directory, RecordingRule())
            val opening = recorder(folder = folder)

            receive(DISARMED_STABILIZE, opening)
            runCurrent()

            folder.recording shouldBeEqualTo START_NAME
        }

    @Tag("REQ-038")
    @Test
    fun `GIVEN a recording WHEN it closes THEN the folder no longer protects it as the one recorded`() =
        testScope.runTest {
            val folder = RecordingsFolder(directory, RecordingRule())
            val closing = recorder(folder = folder)
            receive(DISARMED_STABILIZE, closing)
            runCurrent()

            closing.sessionStopped()
            runCurrent()

            (folder.recording to folder.marker.read()) shouldBeEqualTo (null to START_NAME)
        }

    @Tag("REQ-038")
    @Test
    fun `GIVEN a full disk WHEN the recording fails THEN the folder no longer protects it as the one recorded`() =
        testScope.runTest {
            val folder = RecordingsFolder(directory, RecordingRule())
            val failing = recorder(folder = folder, openOutput = { FailingStream() })

            receive(DISARMED_STABILIZE, failing)
            runCurrent()

            folder.recording shouldBeEqualTo null
        }

    private fun recorder(
        rule: RecordingRule = RecordingRule(),
        folder: RecordingsFolder = RecordingsFolder(directory, rule),
        openOutput: (File) -> OutputStream = ::opened,
        syncOutput: (OutputStream) -> Unit = {},
        timeSource: TimeSource.WithComparableMarks = testScope.testScheduler.timeSource,
    ) = FlightRecorder(
        folder = folder,
        rule = rule,
        activeVehicle = activeVehicle,
        timeSource = timeSource,
        clock = clock,
        scope = testScope.backgroundScope,
        openOutput = openOutput,
        syncOutput = syncOutput,
    )

    private fun opened(file: File): OutputStream = FileOutputStream(file).also(openStreams::add)

    /** As the receive loop does: the first vehicle's HEARTBEAT pins it, then the frame goes to [into]. */
    private fun receive(path: String, into: FlightRecorder = recorder) {
        val message = message(path)
        message?.let(HeartbeatMapper::vehicleStateOf)?.let { activeVehicle.pinFirst(it.systemId, FIRST_SESSION) }
        into.received(raw(path), message)
    }

    private fun TestScope.stopAndWrite() {
        recorder.sessionStopped()
        runCurrent()
    }

    private fun logs(): List<String> = directory.list { _, name -> name.endsWith(".tlog") }.orEmpty().sorted()

    private fun records(name: String) = TlogReader().let { it.read(File(directory, name).readBytes()) + it.finish() }

    private fun frames(name: String): List<List<Byte>> = records(name).map { it.frame.asList() }

    private fun timestamps(name: String): List<Long> = records(name).map { it.timestampMicros }

    private fun oldLog(name: String, sizeBytes: Long, lastModifiedMillis: Long) {
        directory.mkdirs()
        File(directory, name).apply {
            writeBytes(ByteArray(sizeBytes.toInt()))
            setLastModified(lastModifiedMillis)
        }
    }

    private class SettableClock(private var now: Instant) : Clock() {

        fun advance(by: Duration) {
            now = now.plusMillis(by.inWholeMilliseconds)
        }

        fun set(instant: Instant) {
            now = instant
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class FailingStream : OutputStream() {
        override fun write(b: Int) = throw IOException("No space left on device")
    }

    /** A real file that takes its first write and fails every later one, as a disk filling up mid-flight. */
    private class FullAfterFirstWrite(file: File) : FileOutputStream(file) {
        private var writes = 0

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (writes++ > 0) throw IOException("No space left on device")
            super.write(b, off, len)
        }
    }

    /** A real file that adds to [events] when it closes. */
    private class ClosingStream(file: File, private val events: MutableList<String>) : FileOutputStream(file) {
        override fun close() {
            events += CLOSED
            super.close()
        }
    }

    private companion object {
        const val TAG = "FlightRecorder"
        const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
        const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
        const val SECOND_VEHICLE = "derived/heartbeat-sysid-2.bin"
        const val MISSION_PLANNER = "gcs/heartbeat-mission-planner.bin"
        const val SCALED_PRESSURE = "unknown/scaled-pressure.bin"
        const val HEARTBEAT_SIGNED = "derived/heartbeat-signed.bin"
        const val SCALED_PRESSURE_SIGNED = "derived/scaled-pressure-signed.bin"
        const val SETUP_SIGNING = "derived/setup-signing.bin"

        val START: Instant = Instant.parse("2026-10-20T14:30:05Z")
        const val START_MICROS = 1_792_506_605_000_000L
        const val START_NAME = "2026-10-20_14-30-05.tlog"
        const val START_SUFFIXED_NAME = "2026-10-20_14-30-05_1.tlog"
        val LATER = 2.minutes
        const val LATER_MICROS = 120_000_000L
        const val LATER_NAME = "2026-10-20_14-32-05.tlog"
        const val TEN_MINUTES_LATER_NAME = "2026-10-20_14-40-05.tlog"
        val NINE_MINUTES = 9.minutes
        val BEFORE_EPOCH: Instant = Instant.parse("1969-12-31T23:59:59Z")
        const val BEFORE_EPOCH_NAME = "1969-12-31_23-59-59.tlog"

        const val FOUR_MINUTES_LATER_NAME = "2026-10-20_14-34-05.tlog"
        const val SYNCED = "synced"
        const val CLOSED = "closed"

        const val OLDEST_NAME = "2026-10-01_09-00-00.tlog"
        const val IMPORTED_NAME = "imported.tlog"
        const val LIMIT_BYTES = 1_000L

        val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_555)

        fun fixture(path: String): List<Byte> = readFixture(path).asList()

        fun raw(path: String): RawFrame = MavlinkParser(MavlinkMessages).parseRaw(readFixture(path)).single()

        fun message(path: String): ReceivedMessage? = (raw(path) as? RawFrame.Valid)?.let { valid ->
            ReceivedMessage(
                systemId = valid.frame.systemId,
                componentId = valid.frame.componentId,
                message = MavlinkDecoder.decode(valid.frame),
                sender = SENDER,
                session = FIRST_SESSION,
            )
        }
    }
}
