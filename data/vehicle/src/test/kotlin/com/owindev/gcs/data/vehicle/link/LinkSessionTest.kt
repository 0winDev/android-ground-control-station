package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.UdpTransport
import io.mockk.every
import io.mockk.mockk
import java.net.BindException
import java.net.SocketException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-016")
class LinkSessionTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()

    private lateinit var session: LinkSession

    @BeforeEach
    fun setUp() {
        session = LinkSession(transport = transport, scope = testScope.backgroundScope)
    }

    @Test
    fun `GIVEN a real frame split across two datagrams WHEN collecting the messages THEN yields it once complete`() =
        testScope.runTest {
            val frame = readFixture(DISARMED_STABILIZE)
            every { transport.datagrams() } returns flowOf(
                frame.copyOfRange(0, SPLIT),
                frame.copyOfRange(SPLIT, frame.size),
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
}

private const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
private const val SPLIT = 7
private const val GAP_MILLIS = 100L
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
)

/** Delivers [datagram] once and then keeps the socket open with nothing more arriving. */
private fun openWith(datagram: ByteArray): Flow<ByteArray> = flow {
    delay(GAP_MILLIS)
    emit(datagram)
    awaitCancellation()
}

private fun failingWith(failure: Throwable): Flow<ByteArray> = flow { throw failure }
