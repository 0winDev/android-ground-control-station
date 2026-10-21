package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.replay.REPLAY_SENDER
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The session with a live transport that keeps its socket open and a replay that is a plain list of datagrams. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-037")
class LinkSessionReplayTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val recorder: FlightRecorder = mockk()
    private val activeVehicle = ActiveVehicle()
    private val events = mutableListOf<String>()
    private val replayClock = ReplayClock(testScope.testScheduler.timeSource)
    private val pipelineClock = PipelineClock(testScope.testScheduler.timeSource)

    private lateinit var session: LinkSession

    @BeforeEach
    fun setUp() {
        session = LinkSession(
            live = transport,
            scope = testScope.backgroundScope,
            timeSource = testScope.testScheduler.timeSource,
            pipelineClock = pipelineClock,
            recorder = recorder,
            activeVehicle = activeVehicle,
        )
    }

    @Test
    fun `GIVEN an open live session WHEN a replay starts THEN its socket and recording close before it plays`() =
        testScope.runTest {
            recordEvents()
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
                .onCompletion { events += "socket closed" }
            session.messages.onEach { events += "${it.origin} message" }.launchIn(backgroundScope)
            runCurrent()

            session.replay(replayOf(REPLAYED_VEHICLE), replayClock)
            runCurrent()

            events shouldBeEqualTo listOf(
                "live frame recorded",
                "LIVE message",
                "socket closed",
                "recording closed",
                "REPLAY message",
            )
        }

    @Test
    fun `GIVEN a replay WHEN it plays THEN no frame reaches the recorder and the recording is not closed twice`() =
        testScope.runTest {
            recordEvents()
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.replay(replayOf(REPLAYED_VEHICLE, REPLAYED_VEHICLE), replayClock)

            session.messages.launchIn(backgroundScope)
            runCurrent()

            verifyNever {
                recorder.received(any(), any())
                recorder.sent(any())
                recorder.sessionStopped()
            }
        }

    @Test
    fun `GIVEN a replay WHEN it plays THEN every message says it comes from the replay and its sender`() =
        testScope.runTest {
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.replay(replayOf(REPLAYED_VEHICLE), replayClock)

            val result = session.messages.first()

            (result.origin to result.sender) shouldBeEqualTo (DataOrigin.REPLAY to REPLAY_SENDER)
        }

    @Test
    fun `GIVEN a replay starting WHEN reading the origin THEN it is the replay before any message arrives`() {
        session.replay(replayOf(REPLAYED_VEHICLE), replayClock)

        val result = session.session.value.origin

        result shouldBeEqualTo DataOrigin.REPLAY
    }

    @Test
    fun `GIVEN a live vehicle pinned WHEN a replay pins another THEN the replay's is active and the live one kept`() =
        testScope.runTest {
            recordEvents()
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.messages.launchIn(backgroundScope)
            runCurrent()
            session.replay(replayOf(SECOND_VEHICLE), replayClock)

            runCurrent()
            val result = activeVehicle.systemId.value to activeVehicle.liveSystemId

            result shouldBeEqualTo (SystemId(2) to SystemId(1))
        }

    @Test
    fun `GIVEN a replay played to its end WHEN reading the status THEN it has ended with every datagram counted`() =
        testScope.runTest {
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.replay(replayOf(REPLAYED_VEHICLE, REPLAYED_VEHICLE, REPLAYED_VEHICLE), replayClock)
            session.messages.launchIn(backgroundScope)

            runCurrent()
            val result = session.status.value

            result shouldBeEqualTo LinkStatus.ReplayEnded(datagrams = 3)
        }

    @Test
    fun `GIVEN a recording that cannot be read WHEN replaying it THEN the status says the replay failed`() =
        testScope.runTest {
            val failure = IOException("unreadable")
            session.replay(failingWith(failure), replayClock)
            session.messages.launchIn(backgroundScope)

            runCurrent()
            val result = session.status.value

            result shouldBeEqualTo LinkStatus.ReplayFailed(failure)
        }

    @Test
    fun `GIVEN a replay WHEN the live link is given back THEN the live socket opens again and its messages are live`() =
        testScope.runTest {
            recordEvents()
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.replay(replayOf(REPLAYED_VEHICLE), replayClock)
            session.messages.launchIn(backgroundScope)
            runCurrent()

            session.live()
            val result = session.messages.first()

            (result.origin to session.status.value) shouldBeEqualTo (DataOrigin.LIVE to LinkStatus.Running(1))
        }

    @Test
    fun `GIVEN a replay that ended WHEN one of its HEARTBEATs arrives late THEN the next replay's pin stays free`() {
        session.replay(replayOf(), replayClock)
        val ended = session.session.value
        session.replay(replayOf(), replayClock)

        activeVehicle.pinFirst(SystemId(1), ended)
        val result = activeVehicle.systemId.value

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a replay WHEN its source starts THEN the pipeline runs on the replay's time`() = testScope.runTest {
        every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
        session.replay(replayOf(REPLAYED_VEHICLE), replayClock)
        session.messages.launchIn(backgroundScope)
        runCurrent()
        val mark = pipelineClock.markNow()

        delay(PAUSED)
        val result = mark.elapsedNow()

        result shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `GIVEN a replay WHEN the live source starts again THEN the pipeline runs on the device's time`() =
        testScope.runTest {
            recordEvents()
            every { transport.datagrams() } returns stayingOpen(LIVE_VEHICLE)
            session.replay(replayOf(REPLAYED_VEHICLE), replayClock)
            session.messages.launchIn(backgroundScope)
            runCurrent()
            session.live()
            runCurrent()
            val mark = pipelineClock.markNow()

            delay(PAUSED)
            val result = mark.elapsedNow()

            result shouldBeEqualTo PAUSED
        }

    private fun recordEvents() {
        every { recorder.received(any(), any()) } answers { events += "live frame recorded" }
        every { recorder.sent(any()) } just runs
        every { recorder.sessionStopped() } answers { events += "recording closed" }
    }
}

/** A live socket that delivers [fixture] and stays open until cancelled. */
private fun stayingOpen(fixture: String): Flow<Datagram> = flow {
    emit(Datagram(readFixture(fixture), LIVE_ADDRESS))
    awaitCancellation()
}

private fun replayOf(vararg fixtures: String): DatagramSource = object : DatagramSource {
    override fun datagrams(): Flow<Datagram> =
        flowOf(*fixtures.map { Datagram(readFixture(it), REPLAY_SENDER) }.toTypedArray())
}

private fun failingWith(failure: IOException): DatagramSource = object : DatagramSource {
    override fun datagrams(): Flow<Datagram> = flow { throw failure }
}

private const val LIVE_VEHICLE = "heartbeat/disarmed-stabilize.bin"
private const val REPLAYED_VEHICLE = "heartbeat/disarmed-loiter.bin"
private const val SECOND_VEHICLE = "derived/heartbeat-sysid-2.bin"
private val PAUSED = 30.seconds
private val LIVE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
