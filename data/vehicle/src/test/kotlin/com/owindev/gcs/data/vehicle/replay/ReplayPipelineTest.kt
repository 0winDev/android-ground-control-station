package com.owindev.gcs.data.vehicle.replay

import android.util.Log
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.replay.ReplayRule
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.mavlink.TlogWriter
import com.owindev.gcs.core.testing.coVerifyNever
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The whole pipeline as the app wires it, from a real .tlog in a temporary folder to the vehicles, on the pipeline's
 * clock over the test scheduler's time. Only the live socket is a mock: it never delivers unless a test says so, and
 * records anything sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-037")
class ReplayPipelineTest {

    @TempDir
    lateinit var temp: File

    private val testScope = TestScope()
    private val clock = PipelineClock(testScope.testScheduler.timeSource)
    private val transport: UdpTransport = mockk()
    private val recorder: FlightRecorder = ignoringRecorder()
    private val activeVehicle = ActiveVehicle()
    private val store = TelemetryStore()
    private val recordings get() = File(temp, "tlogs").apply { mkdirs() }

    private lateinit var session: LinkSession
    private lateinit var vehicles: DefaultVehicleRepository
    private lateinit var repository: DefaultReplayRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { transport.datagrams() } returns silentSocket()
        session = LinkSession(
            live = transport,
            scope = testScope.backgroundScope,
            timeSource = clock,
            pipelineClock = clock,
            recorder = recorder,
            activeVehicle = activeVehicle,
        )
        val sender = GcsMessageSender(transport, recorder, session)
        vehicles = DefaultVehicleRepository(
            session = session,
            timeSource = clock,
            rule = LinkLossRule(),
            heartbeatSender = GcsHeartbeatSender(sender),
            telemetryRequester = TelemetryRequester(sender, session, store, StalenessRule(), clock),
            telemetryStore = store,
            activeVehicle = activeVehicle,
            scope = testScope.backgroundScope,
        )
        repository = DefaultReplayRepository(
            session = session,
            vehicleRepository = vehicles,
            clock = clock,
            recorder = recorder,
            rule = ReplayRule(LinkLossRule()),
            recordings = recordings,
            replaySource = { file, replayClock ->
                TlogDatagramSource(file, replayClock, StandardTestDispatcher(testScope.testScheduler))
            },
            scope = testScope.backgroundScope,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN a replay paused for longer than the link-loss timeout WHEN observing THEN stays CONNECTED and fresh`() =
        testScope.runTest {
            val recording = recordingOf(*everySecond(count = 10, HEARTBEAT))
            vehicles.vehicles.launchIn(backgroundScope)
            repository.play(recording)
            delay(2_500.milliseconds)
            repository.pause()

            delay(PAUSE)
            val result = vehicles.vehicles.value.getValue(VEHICLE).let { it.link to it.lastHeartbeat.elapsedNow() }

            result shouldBeEqualTo (VehicleLink.CONNECTED to 500.milliseconds)
        }

    @Test
    fun `GIVEN a real gap in the recording WHEN replaying it THEN the link is LOST and CONNECTED again after it`() =
        testScope.runTest {
            val recording = recordingOf(
                0L to HEARTBEAT,
                1_000L to HEARTBEAT,
                6_000L to HEARTBEAT,
                7_000L to HEARTBEAT,
            )
            repository.play(recording)

            val result = linkChanges(count = 4)

            result shouldBeEqualTo listOf(
                null to 0L,
                VehicleLink.CONNECTED to 0L,
                VehicleLink.LOST to 4_000L,
                VehicleLink.CONNECTED to 6_000L,
            )
        }

    @Test
    fun `GIVEN a recording played to its end WHEN time goes on THEN is LOST a timeout after its last record`() =
        testScope.runTest {
            val recording = recordingOf(0L to HEARTBEAT, 1_000L to HEARTBEAT, 2_000L to HEARTBEAT)
            repository.play(recording)

            val result = linkChanges(count = 3).last()

            (result to repository.state.value) shouldBeEqualTo
                ((VehicleLink.LOST to 5_000L) to ReplayState.Ended(recording))
        }

    @Test
    fun `GIVEN a replay of a connected vehicle WHEN it plays THEN nothing is sent and nothing recorded`() =
        testScope.runTest {
            val recording = recordingOf(*everySecond(count = 10, HEARTBEAT), 500L to POSITION)
            vehicles.vehicles.launchIn(backgroundScope)
            repository.play(recording)

            delay(PLAYED_THROUGH)

            coVerifyNever {
                transport.send(any(), any())
            }
            verifyNever {
                recorder.received(any(), any())
                recorder.sent(any())
            }
        }

    @Test
    fun `GIVEN a live vehicle lost before a replay WHEN the replay plays THEN only the replay's vehicle shows`() =
        testScope.runTest {
            every { transport.datagrams() } returns liveSocketWith(HEARTBEAT)
            coEvery { transport.send(any(), any()) } returns SendResult.Sent
            vehicles.vehicles.launchIn(backgroundScope)
            delay(LIVE_LOST)
            val recording = recordingOf(0L to SECOND_VEHICLE)

            repository.play(recording)
            runCurrent()
            val result = vehicles.vehicles.value.mapValues { (_, vehicle) -> vehicle.origin }

            result shouldBeEqualTo mapOf(SystemId(2) to DataOrigin.REPLAY)
        }

    @Test
    fun `GIVEN a replay with a position WHEN another replay without one starts THEN nothing of the first remains`() =
        testScope.runTest {
            vehicles.vehicles.launchIn(backgroundScope)
            repository.play(recordingOf(0L to HEARTBEAT, 100L to POSITION, name = "first.tlog"))
            delay(1.seconds)

            repository.play(recordingOf(0L to SECOND_VEHICLE, name = "second.tlog"))
            delay(1.seconds)
            val result = Triple(vehicles.vehicles.value.keys, vehicles.activeSystemId.value, store.telemetry.value.keys)

            result shouldBeEqualTo Triple(setOf(SystemId(2)), SystemId(2), emptySet())
        }

    @Test
    fun `GIVEN a replay WHEN stopped and played again at once THEN the second replay starts from nothing`() =
        testScope.runTest {
            vehicles.vehicles.launchIn(backgroundScope)
            repository.play(recordingOf(0L to HEARTBEAT, 100L to POSITION, name = "first.tlog"))
            delay(1.seconds)

            repository.stop()
            repository.play(recordingOf(0L to SECOND_VEHICLE, name = "second.tlog"))
            delay(1.seconds)
            val result = vehicles.vehicles.value.keys to store.telemetry.value.keys

            result shouldBeEqualTo (setOf(SystemId(2)) to emptySet())
        }

    @Test
    fun `GIVEN a replay paused right before the link-loss timeout WHEN paused long THEN the watchdog seldom runs`() =
        testScope.runTest {
            val counting = CountingClock(clock)
            val watched = vehiclesOn(counting)
            watched.vehicles.launchIn(backgroundScope)
            repository.play(recordingOf(0L to HEARTBEAT))
            delay(TIMEOUT_EDGE)
            repository.pause()
            val before = counting.reads

            delay(PAUSE)
            val result = counting.reads - before

            (result < MAX_READS_IN_PAUSE) shouldBeEqualTo true
        }

    @Test
    fun `GIVEN a replay WHEN it is stopped THEN its vehicle is forgotten and the live one is active again`() =
        testScope.runTest {
            every { transport.datagrams() } returns liveSocketWith(HEARTBEAT)
            coEvery { transport.send(any(), any()) } returns SendResult.Sent
            vehicles.vehicles.launchIn(backgroundScope)
            delay(LIVE_LOST)
            repository.play(recordingOf(0L to SECOND_VEHICLE))
            runCurrent()

            every { transport.datagrams() } returns silentSocket()
            repository.stop()
            runCurrent()
            val result = vehicles.vehicles.value to vehicles.activeSystemId.value

            result shouldBeEqualTo (emptyMap<SystemId, Vehicle>() to VEHICLE)
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a QGroundControl log with its own frames WHEN replaying it THEN only the vehicle is heard`() =
        testScope.runTest {
            val recording = File(recordings, "qgroundcontrol.tlog").apply {
                writeBytes(readFixture("replay/qgroundcontrol.tlog"))
            }
            vehicles.vehicles.launchIn(backgroundScope)
            repository.play(recording)

            delay(PLAYED_THROUGH)
            val result = Triple(
                vehicles.vehicles.value.keys,
                vehicles.activeSystemId.value,
                store.telemetry.value.mapValues { (_, telemetry) -> telemetry.origin },
            )

            result shouldBeEqualTo Triple(setOf(VEHICLE), VEHICLE, mapOf(VEHICLE to DataOrigin.REPLAY))
        }

    /** The active vehicle's link each time it changes, with the virtual ms it changed at, from now. */
    private suspend fun TestScope.linkChanges(count: Int): List<Pair<VehicleLink?, Long>> {
        val start = currentTime
        return vehicles.vehicles
            .map { it[VEHICLE]?.link }
            .distinctUntilChanged()
            .map { it to currentTime - start }
            .take(count)
            .toList()
    }

    private fun recordingOf(vararg records: Pair<Long, String>, name: String = "flight.tlog"): File =
        File(recordings, name).apply {
            writeBytes(
                records.sortedBy { it.first }.fold(ByteArray(0)) { log, (atMillis, fixture) ->
                    log + TlogWriter.record(START + atMillis * MICROS_PER_MILLI, readFixture(fixture))
                },
            )
        }

    /** A vehicle repository like the app's, timed on [timeSource] instead of the pipeline's clock itself. */
    private fun vehiclesOn(timeSource: TimeSource.WithComparableMarks): DefaultVehicleRepository {
        val sender = GcsMessageSender(transport, recorder, session)
        return DefaultVehicleRepository(
            session = session,
            timeSource = timeSource,
            rule = LinkLossRule(),
            heartbeatSender = GcsHeartbeatSender(sender),
            telemetryRequester = TelemetryRequester(sender, session, store, StalenessRule(), clock),
            telemetryStore = store,
            activeVehicle = activeVehicle,
            scope = testScope.backgroundScope,
        )
    }

    private fun TestScope.liveSocketWith(fixture: String): Flow<Datagram> = flow {
        emit(Datagram(readFixture(fixture), LIVE_ADDRESS))
        awaitCancellation()
    }
}

/** [clock]'s time, counting how often it is read: each read is a wake-up of whatever reads it. */
private class CountingClock(clock: TimeSource.WithComparableMarks) : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val origin = clock.markNow()
    var reads = 0
        private set

    override fun read(): Long {
        reads++
        return origin.elapsedNow().inWholeNanoseconds
    }
}

private fun everySecond(count: Int, fixture: String): Array<Pair<Long, String>> =
    Array(count) { index -> index * 1_000L to fixture }

private fun silentSocket(): Flow<Datagram> = flow { awaitCancellation() }

/** 2026-10-16 17:09:58 UTC, in µs; a multiple of 4, so the writer keeps it whole. */
private const val START = 1_760_634_598_000_000L
private const val MICROS_PER_MILLI = 1_000L
private const val HEARTBEAT = "heartbeat/disarmed-stabilize.bin"
private const val POSITION = "telemetry/global-position-int.bin"
private const val SECOND_VEHICLE = "derived/heartbeat-sysid-2.bin"
private val VEHICLE = SystemId(1)
private val PAUSE = 30.seconds
private val PLAYED_THROUGH = 40.seconds
private val LIVE_LOST: Duration = 4.seconds
private val TIMEOUT_EDGE = LinkLossRule.DEFAULT_TIMEOUT - 1.milliseconds

/** 20 checks a second at most over the pause, with room: an unbounded re-check would read the clock every 1 ms. */
private const val MAX_READS_IN_PAUSE = 2_000
private val LIVE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
