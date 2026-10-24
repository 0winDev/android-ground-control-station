package com.owindev.gcs.data.vehicle.replay

import com.owindev.gcs.core.mavlink.TlogWriter
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real frames in a real file in a temporary folder; time is the test scheduler's. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-037")
class TlogDatagramSourceTest {

    @TempDir
    lateinit var temp: File

    private val testScope = TestScope()
    private val clock = ReplayClock(testScope.testScheduler.timeSource)
    private val recording get() = File(temp, "flight.tlog")
    private val gaps = mutableListOf<Pair<Long, Duration?>>()

    private lateinit var source: TlogDatagramSource

    @BeforeEach
    fun setUp() {
        source = TlogDatagramSource(
            recording = recording,
            clock = clock,
            ioDispatcher = StandardTestDispatcher(testScope.testScheduler),
            onGap = { gaps += testScope.currentTime to it },
        )
    }

    @Test
    fun `GIVEN a playing replay WHEN collecting THEN delivers each frame from the replay sender, in order`() =
        testScope.runTest {
            recording.writeRecords(START to STABILIZE, START + 1_000_000 to GUIDED, START + 2_000_000 to LOITER)
            clock.play()

            val result = source.datagrams().toList()

            result shouldBeEqualTo listOf(STABILIZE, GUIDED, LOITER).map { Datagram(it, REPLAY_SENDER) }
        }

    @Test
    fun `GIVEN a playing replay WHEN collecting THEN delivers each record at its recorded pace`() = testScope.runTest {
        recording.writeRecords(START to STABILIZE, START + 1_000_000 to GUIDED, START + 2_500_000 to LOITER)
        clock.play()

        val result = arrivalTimes()

        result shouldBeEqualTo listOf(0L, 1_000L, 2_500L)
    }

    @Test
    fun `GIVEN a replay not played yet WHEN collecting THEN delivers nothing until it plays`() = testScope.runTest {
        recording.writeRecords(START to STABILIZE, START + 1_000_000 to GUIDED)
        backgroundScope.launch {
            delay(PAUSE)
            clock.play()
        }

        val result = arrivalTimes()

        result shouldBeEqualTo listOf(PAUSE.inWholeMilliseconds, PAUSE.inWholeMilliseconds + 1_000L)
    }

    @Test
    fun `GIVEN a replay paused between two records WHEN collecting THEN the second waits for the whole pause`() =
        testScope.runTest {
            recording.writeRecords(START to STABILIZE, START + 2_000_000 to GUIDED)
            clock.play()
            backgroundScope.launch {
                delay(1.seconds)
                clock.pause()
                delay(PAUSE)
                clock.play()
            }

            val result = arrivalTimes()

            result shouldBeEqualTo listOf(0L, 2_000L + PAUSE.inWholeMilliseconds)
        }

    @Test
    fun `GIVEN a wall clock set back between two records WHEN collecting THEN the replay neither stalls nor waits`() =
        testScope.runTest {
            val setBack = START - 1.hours.inWholeMicroseconds
            recording.writeRecords(START to STABILIZE, setBack to GUIDED, setBack + 1_000_000 to LOITER)
            clock.play()

            val result = arrivalTimes()

            result shouldBeEqualTo listOf(0L, 0L, 1_000L)
        }

    @Test
    fun `GIVEN a wall clock set far forwards between two records WHEN collecting THEN waits only the maximum`() =
        testScope.runTest {
            val setForwards = START + 1.hours.inWholeMicroseconds
            recording.writeRecords(START to STABILIZE, setForwards to GUIDED, setForwards + 1_000_000 to LOITER)
            clock.play()

            val result = arrivalTimes()

            result shouldBeEqualTo listOf(
                0L,
                MAX_RECORD_WAIT.inWholeMilliseconds,
                MAX_RECORD_WAIT.inWholeMilliseconds + 1_000L,
            )
        }

    @Test
    fun `GIVEN a gap shorter than the maximum WHEN collecting THEN keeps the whole gap`() = testScope.runTest {
        val gap = 5.seconds
        recording.writeRecords(START to STABILIZE, START + gap.inWholeMicroseconds to GUIDED)
        clock.play()

        val result = arrivalTimes()

        result shouldBeEqualTo listOf(0L, gap.inWholeMilliseconds)
    }

    @Test
    fun `GIVEN more records than one read holds WHEN collecting THEN delivers every one`() = testScope.runTest {
        val count = 500
        recording.writeRecords(*Array(count) { index -> START + index * 10_000L to STABILIZE })
        clock.play()

        val result = source.datagrams().toList().size

        result shouldBeEqualTo count
    }

    @Test
    fun `GIVEN bytes that are not records before the log WHEN collecting THEN still delivers the records`() =
        testScope.runTest {
            recording.writeBytes(ByteArray(GARBAGE_LENGTH))
            recording.appendBytes(records(START to STABILIZE, START + 1_000_000 to GUIDED, START + 2_000_000 to LOITER))
            clock.play()

            val result = source.datagrams().map { it.payload.toList() }.toList()

            result shouldBeEqualTo listOf(STABILIZE, GUIDED, LOITER).map { it.toList() }
        }

    @Test
    fun `GIVEN a recording that does not exist WHEN collecting THEN fails with an I-O error`() = testScope.runTest {
        clock.play()

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.datagrams().toList()
                null
            } catch (e: IOException) {
                e
            }
        }
        val result = deferred.await()

        result shouldBeInstanceOf FileNotFoundException::class
    }

    @Test
    fun `GIVEN two stamps whose difference overflows a Long WHEN waiting THEN waits the maximum`() {
        val previous = -1L

        val result = waitBetween(previous = previous, next = Long.MAX_VALUE)

        result shouldBeEqualTo MAX_RECORD_WAIT
    }

    @Test
    fun `GIVEN two stamps a few milliseconds apart WHEN waiting THEN waits their difference`() {
        val previous = START

        val result = waitBetween(previous = previous, next = START + 20_000)

        result shouldBeEqualTo 20.milliseconds
    }

    @Test
    fun `GIVEN a gap longer than the maximum WHEN collecting THEN reports what it skips until the next record`() =
        testScope.runTest {
            recording.writeRecords(START to STABILIZE, START + GAP.inWholeMicroseconds to GUIDED)
            clock.play()

            source.datagrams().toList()

            gaps shouldBeEqualTo listOf(0L to GAP - MAX_RECORD_WAIT, MAX_RECORD_WAIT.inWholeMilliseconds to null)
        }

    @Test
    fun `GIVEN a pause while skipping a gap WHEN collecting THEN the gap stays reported through the pause`() =
        testScope.runTest {
            recording.writeRecords(START to STABILIZE, START + GAP.inWholeMicroseconds to GUIDED)
            clock.play()
            backgroundScope.launch {
                delay(1.seconds)
                clock.pause()
                delay(PAUSE)
                clock.play()
            }

            source.datagrams().toList()

            gaps shouldBeEqualTo listOf(
                0L to GAP - MAX_RECORD_WAIT,
                (MAX_RECORD_WAIT + PAUSE).inWholeMilliseconds to null,
            )
        }

    @Test
    fun `GIVEN a gap of exactly the maximum WHEN collecting THEN nothing is skipped`() = testScope.runTest {
        recording.writeRecords(START to STABILIZE, START + MAX_RECORD_WAIT.inWholeMicroseconds to GUIDED)
        clock.play()

        source.datagrams().toList()

        gaps shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN a wall clock set back WHEN collecting THEN nothing is reported as skipped`() = testScope.runTest {
        recording.writeRecords(START to STABILIZE, START - 1.hours.inWholeMicroseconds to GUIDED)
        clock.play()

        source.datagrams().toList()

        gaps shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN two stamps whose difference overflows a Long WHEN naming what is skipped THEN nothing`() {
        val previous = -1L

        val result = skippedBetween(previous = previous, next = Long.MAX_VALUE)

        result shouldBeEqualTo null
    }

    private suspend fun TestScope.arrivalTimes(): List<Long> = source.datagrams().map { currentTime }.toList()
}

private fun File.writeRecords(vararg records: Pair<Long, ByteArray>) = writeBytes(records(*records))

private fun records(vararg records: Pair<Long, ByteArray>): ByteArray =
    records.fold(ByteArray(0)) { log, (timestamp, frame) -> log + TlogWriter.record(timestamp, frame) }

/** 2026-10-16 17:09:58 UTC, in µs; a multiple of 4, so the writer keeps it whole. */
private const val START = 1_760_634_598_000_000L
private const val GARBAGE_LENGTH = 37
private val PAUSE = 30.seconds

/** A downlink cut of 8 min 10 s kept in the log: the replay waits 10 s of it and skips 8 min. */
private val GAP = 490.seconds
private val STABILIZE = readFixture("heartbeat/disarmed-stabilize.bin")
private val GUIDED = readFixture("heartbeat/disarmed-guided.bin")
private val LOITER = readFixture("heartbeat/disarmed-loiter.bin")
