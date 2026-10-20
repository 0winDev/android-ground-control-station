package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.every
import io.mockk.mockk
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-016")
class LinkSessionTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val recorder: FlightRecorder = ignoringRecorder()
    private val activeVehicle = ActiveVehicle()

    private lateinit var session: LinkSession

    @BeforeEach
    fun setUp() {
        session = LinkSession(
            transport = transport,
            scope = testScope.backgroundScope,
            timeSource = testScope.testScheduler.timeSource,
            recorder = recorder,
            activeVehicle = activeVehicle,
        )
    }

    @Test
    fun `GIVEN a real frame split across two datagrams WHEN collecting the messages THEN yields it once complete`() =
        testScope.runTest {
            val frame = readFixture(DISARMED_STABILIZE)
            every { transport.datagrams() } returns flowOf(
                Datagram(frame.copyOfRange(0, SPLIT), VEHICLE_ADDRESS),
                Datagram(frame.copyOfRange(SPLIT, frame.size), VEHICLE_ADDRESS),
            )

            val result = session.messages.take(1).toList()

            result shouldBeEqualTo listOf(disarmedStabilizeFromAutopilot())
        }

    @Test
    fun `GIVEN a HEARTBEAT already delivered WHEN a late subscriber collects the messages THEN receives no old one`() =
        testScope.runTest {
            every { transport.datagrams() } returns openWith(readFixture(DISARMED_STABILIZE))
            session.messages.launchIn(backgroundScope)
            delay(GAP_MILLIS * 2)

            val result = withTimeoutOrNull(LATE_WAIT) { session.messages.first() }

            result.shouldBeNull()
        }

    @Test
    fun `GIVEN the port is in use WHEN a late subscriber observes the status THEN sees Unavailable`() =
        testScope.runTest {
            every { transport.datagrams() } returns failingWith(BindException())
            session.messages.launchIn(backgroundScope)
            delay(GAP_MILLIS)

            val result = session.whileOpen(session.status).first()

            result shouldBeEqualTo LinkStatus.Unavailable
        }

    @Test
    fun `GIVEN an unexpected socket error WHEN observing the status THEN reports it as Failed`() = testScope.runTest {
        val failure = SocketException()
        every { transport.datagrams() } returns failingWith(failure)

        val result = session.whileOpen(session.status).take(2).toList()

        result shouldBeEqualTo listOf(LinkStatus.Running(datagrams = 0), LinkStatus.Failed(failure))
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket fails while observed WHEN HEARTBEATs resume THEN the link reopens and reads them`() =
        testScope.runTest {
            val calls = datagramsAnswering(failingWith(SocketException()), openWith(readFixture(DISARMED_STABILIZE)))

            val result = session.messages.first()

            result shouldBeEqualTo disarmedStabilizeFromAutopilot()
            calls shouldBeEqualTo listOf(0L, RETRY_FIRST_MILLIS)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket fails while observed WHEN it reopens and works THEN goes Failed then Recovering`() =
        testScope.runTest {
            val failure = SocketException()
            datagramsAnswering(failingWith(failure), openWith(readFixture(DISARMED_STABILIZE)))

            val result = session.whileOpen(session.status).take(3).toList()

            result shouldBeEqualTo listOf(
                LinkStatus.Running(datagrams = 0),
                LinkStatus.Failed(failure),
                LinkStatus.Recovering(datagrams = 1),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a reopened socket WHEN it keeps working for the stable time THEN the status goes back to Running`() =
        testScope.runTest {
            val failure = SocketException()
            datagramsAnswering(
                failingWith(failure),
                deliversEvery(readFixture(DISARMED_STABILIZE), STABLE_PERIOD_MILLIS, times = UNTIL_STABLE_DATAGRAMS),
            )

            val result = session.whileOpen(session.status).take(7).toList()

            result shouldBeEqualTo listOf(
                LinkStatus.Running(datagrams = 0),
                LinkStatus.Failed(failure),
                LinkStatus.Recovering(datagrams = 1),
                LinkStatus.Recovering(datagrams = 2),
                LinkStatus.Recovering(datagrams = 3),
                LinkStatus.Recovering(datagrams = 4),
                LinkStatus.Running(datagrams = 5),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a socket that fails after each datagram WHEN observed for long THEN it stays Recovering`() =
        testScope.runTest {
            val failure = SocketException()
            val statuses = mutableListOf<LinkStatus>()
            datagramsAnswering(deliversThenFails(readFixture(DISARMED_STABILIZE), failure))
            backgroundScope.launch { session.whileOpen(session.status).toList(statuses) }

            delay(LONG_WAIT)

            statuses.takeWhile { it !is LinkStatus.Recovering }.last() shouldBeEqualTo LinkStatus.Failed(failure)
            statuses.dropWhile { it !is LinkStatus.Recovering }.map { it::class }.distinct() shouldBeEqualTo
                listOf(LinkStatus.Recovering::class)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a socket that fails after each datagram WHEN observed THEN the backoff keeps growing`() =
        testScope.runTest {
            val calls = datagramsAnswering(deliversThenFails(readFixture(DISARMED_STABILIZE), SocketException()))
            session.messages.launchIn(backgroundScope)

            delay(UNTIL_SIXTH_FLAPPING_ATTEMPT)

            calls shouldBeEqualTo listOf(0L, 1_100L, 3_200L, 7_300L, 12_400L, 17_500L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a recovering link WHEN a reopened socket stays silent THEN goes back to Failed after the stable time`() =
        testScope.runTest {
            val failure = SocketException()
            val statuses = mutableListOf<Pair<Long, LinkStatus>>()
            val start = testScheduler.timeSource.markNow()
            datagramsAnswering(
                deliversThenFails(readFixture(DISARMED_STABILIZE), SocketException()),
                deliversThenFails(readFixture(DISARMED_STABILIZE), failure),
                openWith(null),
            )
            backgroundScope.launch {
                session.whileOpen(session.status).collect { statuses += start.elapsedNow().inWholeMilliseconds to it }
            }

            delay(LONG_WAIT)

            statuses.takeLast(2) shouldBeEqualTo listOf(
                FLAPPED_AT_MILLIS to LinkStatus.Recovering(datagrams = 2),
                FLAPPED_AT_MILLIS + STABLE_MILLIS to LinkStatus.Failed(failure),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a recovering link WHEN a reopened socket fails with no datagram THEN goes back to Failed`() =
        testScope.runTest {
            val failure = SocketException()
            val statuses = mutableListOf<LinkStatus>()
            datagramsAnswering(
                deliversThenFails(readFixture(DISARMED_STABILIZE), SocketException()),
                deliversThenFails(readFixture(DISARMED_STABILIZE), SocketException()),
                failingWith(failure),
            )
            backgroundScope.launch { session.whileOpen(session.status).toList(statuses) }

            delay(UNTIL_SIXTH_FLAPPING_ATTEMPT)

            statuses.takeLast(2) shouldBeEqualTo listOf(
                LinkStatus.Recovering(datagrams = 2),
                LinkStatus.Failed(failure),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket keeps failing WHEN observed THEN it is reopened with a bounded backoff`() =
        testScope.runTest {
            val calls = datagramsAnswering(failingWith(SocketException()))
            session.messages.launchIn(backgroundScope)

            delay(UNTIL_SIXTH_ATTEMPT)

            calls shouldBeEqualTo listOf(0L, 1_000L, 3_000L, 7_000L, 12_000L, 17_000L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link recovered WHEN it fails again THEN the backoff starts from the first delay`() =
        testScope.runTest {
            val calls = datagramsAnswering(
                failingWith(SocketException()),
                deliversEvery(
                    readFixture(DISARMED_STABILIZE),
                    STABLE_PERIOD_MILLIS,
                    times = UNTIL_STABLE_DATAGRAMS,
                    thenFailure = SocketException(),
                ),
                failingWith(SocketException()),
            )
            session.messages.launchIn(backgroundScope)

            delay(UNTIL_THIRD_ATTEMPT)

            calls shouldBeEqualTo listOf(0L, RETRY_FIRST_MILLIS, 17_000L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the port is in use from the start WHEN observed for long THEN it is never retried`() =
        testScope.runTest {
            every { transport.datagrams() } returns failingWith(BindException())
            session.messages.launchIn(backgroundScope)

            delay(LONG_WAIT)

            verifyOnce {
                transport.datagrams()
            }
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the port is taken while reopening WHEN it frees THEN retrying is reported and the link reopens`() =
        testScope.runTest {
            val failure = SocketException()
            val calls = datagramsAnswering(
                failingWith(failure),
                failingWith(BindException()),
                openWith(readFixture(DISARMED_STABILIZE)),
            )

            val result = session.whileOpen(session.status).take(4).toList()

            result shouldBeEqualTo listOf(
                LinkStatus.Running(datagrams = 0),
                LinkStatus.Failed(failure),
                LinkStatus.UnavailableRetrying,
                LinkStatus.Recovering(datagrams = 1),
            )
            calls shouldBeEqualTo listOf(0L, 1_000L, 3_000L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the datagrams end without an error WHEN observed THEN it is a failure and the link reopens`() =
        testScope.runTest {
            val calls = datagramsAnswering(emptyFlow(), openWith(readFixture(DISARMED_STABILIZE)))

            val result = session.whileOpen(session.status).take(3).toList()

            result[1] shouldBeInstanceOf LinkStatus.Failed::class
            result[2] shouldBeEqualTo LinkStatus.Recovering(datagrams = 1)
            calls shouldBeEqualTo listOf(0L, RETRY_FIRST_MILLIS)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the last subscriber leaves during the backoff WHEN time passes THEN the link is not reopened`() =
        testScope.runTest {
            val calls = datagramsAnswering(failingWith(SocketException()))
            val subscriber = session.messages.launchIn(backgroundScope)
            delay(GAP_MILLIS)
            subscriber.cancel()

            delay(LONG_WAIT)

            calls shouldBeEqualTo listOf(0L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a failure that is not I-O WHEN observed THEN it crashes the session and is not retried`() =
        testScope.runTest {
            val crashes = mutableListOf<Throwable>()
            val appScope = CoroutineScope(
                backgroundScope.coroutineContext +
                    SupervisorJob(backgroundScope.coroutineContext.job) +
                    CoroutineExceptionHandler { _, crash -> crashes += crash },
            )
            val calls = datagramsAnswering(failingWith(IllegalStateException()))
            LinkSession(
                transport = transport,
                scope = appScope,
                timeSource = testScheduler.timeSource,
                recorder = ignoringRecorder(),
                activeVehicle = ActiveVehicle(),
            ).messages.launchIn(backgroundScope)

            delay(LONG_WAIT)

            crashes.map { it::class } shouldBeEqualTo listOf(IllegalStateException::class)
            calls shouldBeEqualTo listOf(0L)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN half a frame before a failure WHEN the rest arrives on the new socket THEN no message is made`() =
        testScope.runTest {
            val frame = readFixture(DISARMED_STABILIZE)
            datagramsAnswering(
                deliversThenFails(frame.copyOfRange(0, SPLIT), SocketException()),
                openWith(frame.copyOfRange(SPLIT, frame.size)),
            )

            val result = withTimeoutOrNull(LONG_WAIT) { session.messages.first() }

            result.shouldBeNull()
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link reopened but nothing arrives WHEN observing the status THEN it stays Failed`() =
        testScope.runTest {
            val failure = SocketException()
            datagramsAnswering(failingWith(failure), openWith(null))
            session.messages.launchIn(backgroundScope)

            delay(LONG_WAIT)

            session.status.value shouldBeEqualTo LinkStatus.Failed(failure)
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a reopened socket WHEN the last subscriber leaves THEN the session stops and starts clean`() =
        testScope.runTest {
            datagramsAnswering(failingWith(SocketException()), openWith(readFixture(DISARMED_STABILIZE)))
            session.messages.first()

            delay(GAP_MILLIS)

            session.status.value shouldBeEqualTo LinkStatus.Running(datagrams = 0)
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN an unknown message and a HEARTBEAT in one datagram WHEN collecting THEN both are recorded`() =
        testScope.runTest {
            val unknown = readFixture(SCALED_PRESSURE)
            val heartbeat = readFixture(DISARMED_STABILIZE)
            every { transport.datagrams() } returns openWith(unknown + heartbeat)

            val result = session.messages.first()

            result shouldBeEqualTo disarmedStabilizeFromAutopilot()
            verifyOnce {
                recorder.received(rawFrameOf(unknown), null)
                recorder.received(rawFrameOf(heartbeat), disarmedStabilizeFromAutopilot())
            }
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN an observed session WHEN the last subscriber leaves THEN the recorder is told it stopped`() =
        testScope.runTest {
            every { transport.datagrams() } returns openWith(readFixture(DISARMED_STABILIZE))
            session.messages.first()

            delay(GAP_MILLIS)

            verifyOnce {
                recorder.sessionStopped()
            }
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a ground station's HEARTBEAT then two vehicles' WHEN collecting THEN the first vehicle is pinned`() =
        testScope.runTest {
            val heartbeats = readFixture(MISSION_PLANNER) +
                readFixture(SECOND_VEHICLE) +
                readFixture(DISARMED_STABILIZE)
            every { transport.datagrams() } returns openWith(heartbeats)

            session.messages.take(3).toList()

            activeVehicle.systemId.value shouldBeEqualTo SystemId(2)
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN the first vehicle's HEARTBEAT WHEN the recorder gets it THEN its vehicle is already pinned`() =
        testScope.runTest {
            var pinnedWhenRecorded: SystemId? = null
            every { recorder.received(any(), any()) } answers { pinnedWhenRecorded = activeVehicle.systemId.value }
            every { transport.datagrams() } returns openWith(readFixture(DISARMED_STABILIZE))

            session.messages.first()

            pinnedWhenRecorded shouldBeEqualTo SystemId(1)
        }

    /**
     * Answers each `datagrams()` call with the next of [flows], repeating the last one, and records when each
     * call was made, in virtual ms since this was called.
     */
    private fun TestScope.datagramsAnswering(vararg flows: Flow<Datagram>): List<Long> {
        val calls = mutableListOf<Long>()
        val start = testScheduler.timeSource.markNow()
        val queue = ArrayDeque(flows.toList())
        every { transport.datagrams() } answers {
            calls += start.elapsedNow().inWholeMilliseconds
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        return calls
    }
}

private const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
private const val SCALED_PRESSURE = "unknown/scaled-pressure.bin"
private const val MISSION_PLANNER = "gcs/heartbeat-mission-planner.bin"
private const val SECOND_VEHICLE = "derived/heartbeat-sysid-2.bin"
private const val SPLIT = 7
private const val GAP_MILLIS = 100L
private const val RETRY_FIRST_MILLIS = 1_000L

/** Attempts at 0, 1, 3, 7, 12 and 17 s: 1, 2 and 4 s of backoff, then 5 s each time. */
private const val UNTIL_SIXTH_ATTEMPT = 17_500L

/**
 * Failure at 0 and reopened at 1 s; datagrams at 4, 7, 10, 13 and 16 s, recovered at 16 s (12 s after the first,
 * well past the stable time) and a failure; reopened 1 s later, at 17 s, not 2 s later.
 */
private const val UNTIL_THIRD_ATTEMPT = 17_500L

/** Datagrams this far apart reach the stable time with the fifth: 9 s after the first is short, 12 s is past it. */
private const val STABLE_PERIOD_MILLIS = 3_000L
private const val UNTIL_STABLE_DATAGRAMS = 5

/** Each socket works for 0.1 s: attempts 0.1 s plus 1, 2, 4, 5 and 5 s apart, never back to 1 s. */
private const val UNTIL_SIXTH_FLAPPING_ATTEMPT = 18_000L

/** The second socket opens at 1.1 s, delivers its datagram and fails at 1.2 s. */
private const val FLAPPED_AT_MILLIS = 1_200L
private const val STABLE_MILLIS = 10_000L
private const val LONG_WAIT = 60_000L
private val LATE_WAIT = 1.seconds

/** `heartbeat/disarmed-stabilize.bin` as pymavlink decodes it (see `src/test/resources/README.md`). */
private fun disarmedStabilizeFromAutopilot() = ReceivedMessage(
    systemId = 1,
    componentId = 1,
    message = MavlinkMessage.Heartbeat(
        type = 2,
        autopilot = 3,
        baseMode = 81,
        customMode = 0,
        systemStatus = 3,
        mavlinkVersion = 3,
    ),
    sender = VEHICLE_ADDRESS,
)

/** [frame] as the codec delimits it on its own. */
private fun rawFrameOf(frame: ByteArray): RawFrame = MavlinkParser(MavlinkMessages).parseRaw(frame).single()

/** Delivers [datagram] once (if any) and then keeps the socket open with nothing more arriving. */
private fun openWith(datagram: ByteArray?): Flow<Datagram> = flow {
    delay(GAP_MILLIS)
    if (datagram != null) emit(Datagram(datagram, VEHICLE_ADDRESS))
    awaitCancellation()
}

private fun failingWith(failure: Throwable): Flow<Datagram> = flow { throw failure }

/**
 * Delivers [datagram] [times] times, [periodMillis] apart, and then fails with [thenFailure], or stays open with
 * nothing more arriving if there is none.
 */
private fun deliversEvery(
    datagram: ByteArray,
    periodMillis: Long,
    times: Int,
    thenFailure: Throwable? = null,
): Flow<Datagram> = flow {
    repeat(times) {
        delay(periodMillis)
        emit(Datagram(datagram, VEHICLE_ADDRESS))
    }
    if (thenFailure != null) throw thenFailure
    awaitCancellation()
}

/** Delivers [datagram] once and then fails with [failure], as a socket that breaks after working. */
private fun deliversThenFails(datagram: ByteArray, failure: Throwable): Flow<Datagram> = flow {
    delay(GAP_MILLIS)
    emit(Datagram(datagram, VEHICLE_ADDRESS))
    throw failure
}

/** Where SITL's datagrams come from, as the transport reports it. */
private val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
