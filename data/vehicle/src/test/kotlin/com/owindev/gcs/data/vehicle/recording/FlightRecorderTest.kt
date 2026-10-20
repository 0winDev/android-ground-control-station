package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.mavlink.TlogReader
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
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
            small.received(raw(DISARMED_STABILIZE), message(DISARMED_STABILIZE))

            small.sessionStopped()
            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME, IMPORTED_NAME)
        }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a recording over the limit by itself WHEN it closes THEN every other log goes but never it`() =
        testScope.runTest {
            val tiny = recorder(RecordingRule(maxTotalBytes = 1))
            tiny.received(raw(DISARMED_STABILIZE), message(DISARMED_STABILIZE))
            runCurrent()
            oldLog(OLDEST_NAME, 1, lastModifiedMillis = 1)
            tiny.sessionStopped()

            runCurrent()

            logs() shouldBeEqualTo listOf(START_NAME)
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
            failing.received(raw(DISARMED_STABILIZE), message(DISARMED_STABILIZE))
            runCurrent()
            failing.received(raw(DISARMED_LOITER), message(DISARMED_LOITER))

            runCurrent()

            verifyOnce {
                Log.w(TAG, any<String>(), any<IOException>())
            }
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

    private fun recorder(
        rule: RecordingRule = RecordingRule(),
        openOutput: ((File) -> OutputStream)? = null,
    ): FlightRecorder {
        val timeSource = testScope.testScheduler.timeSource
        val scope = testScope.backgroundScope
        return if (openOutput == null) {
            FlightRecorder(directory, rule, timeSource, clock, scope)
        } else {
            FlightRecorder(directory, rule, timeSource, clock, scope, openOutput)
        }
    }

    private fun receive(path: String) = recorder.received(raw(path), message(path))

    private fun TestScope.stopAndWrite() {
        recorder.sessionStopped()
        runCurrent()
    }

    private fun logs(): List<String> = directory.list().orEmpty().sorted()

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

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class FailingStream : OutputStream() {
        override fun write(b: Int) = throw IOException("No space left on device")
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
            )
        }
    }
}
