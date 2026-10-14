package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.UdpTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-005")
class DefaultLinkRepositoryTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()

    private lateinit var repository: DefaultLinkRepository

    @BeforeEach
    fun setUp() {
        val session = LinkSession(
            transport = transport,
            scope = testScope.backgroundScope,
            timeSource = testScope.testScheduler.timeSource,
        )
        repository = DefaultLinkRepository(session = session, scope = testScope.backgroundScope)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN datagrams arriving one at a time WHEN observing the traffic THEN counts each one`() = testScope.runTest {
        every { transport.port } returns PORT
        every { transport.datagrams() } returns datagramsArriving(count = 3)

        val result = repository.traffic.take(4).toList()

        result shouldBeEqualTo listOf(receiving(0), receiving(1), receiving(2), receiving(3))
    }

    @Test
    fun `GIVEN the port is in use WHEN observing the traffic THEN becomes Unavailable`() = testScope.runTest {
        every { transport.port } returns PORT
        every { transport.datagrams() } returns failingWith(BindException())

        val result = repository.traffic.take(2).toList()

        result shouldBeEqualTo listOf(receiving(0), LinkTraffic.Unavailable(port = PORT))
    }

    @Test
    fun `GIVEN an unexpected socket error WHEN observing the traffic THEN becomes Failed`() = testScope.runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { transport.port } returns PORT
        every { transport.datagrams() } returns failingWith(SocketException())

        val result = repository.traffic.take(2).toList()

        result shouldBeEqualTo listOf(receiving(0), LinkTraffic.Failed)
    }

    @Test
    fun `GIVEN an unexpected socket error WHEN observing the traffic THEN logs its cause`() = testScope.runTest {
        val failure = SocketException()
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { transport.port } returns PORT
        every { transport.datagrams() } returns failingWith(failure)

        repository.traffic.take(2).toList()

        verifyOnce {
            Log.w(any(), any<String>(), failure)
        }
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket fails and then works WHEN observing the traffic THEN goes Failed and then Recovering`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { transport.port } returns PORT
            every { transport.datagrams() } returnsMany listOf(failingWith(SocketException()), datagramsArriving(1))

            val result = repository.traffic.take(3).toList()

            result shouldBeEqualTo listOf(receiving(0), LinkTraffic.Failed, recovering(1))
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the reopened link stays up WHEN observing the traffic THEN goes back to Receiving`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { transport.port } returns PORT
            every { transport.datagrams() } returnsMany listOf(failingWith(SocketException()), stableDatagrams())

            val result = repository.traffic.take(7).toList()

            result shouldBeEqualTo listOf(
                receiving(0),
                LinkTraffic.Failed,
                recovering(1),
                recovering(2),
                recovering(3),
                recovering(4),
                receiving(5),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket fails and then works WHEN observing the traffic THEN the recovery is logged once`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { transport.port } returns PORT
            every { transport.datagrams() } returnsMany listOf(failingWith(SocketException()), stableDatagrams())

            repository.traffic.take(7).toList()

            verifyOnce {
                Log.i(any(), "Vehicle link reopened")
            }
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a socket that fails after each datagram WHEN observed for long THEN logs one failure and no recovery`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { transport.port } returns PORT
            every { transport.datagrams() } answers { datagramThenFailure() }
            repository.traffic.launchIn(backgroundScope)

            delay(LONG_WAIT)

            verifyOnce {
                Log.w(any(), any<String>(), any())
            }
            verifyNever {
                Log.i(any(), any())
            }
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a session stopped while failing WHEN the next session fails THEN that failure is logged too`() =
        testScope.runTest {
            var warnings = 0
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } answers {
                warnings++
                0
            }
            every { transport.port } returns PORT
            every { transport.datagrams() } answers { failingWith(SocketException()) }
            val firstObserver = repository.traffic.launchIn(backgroundScope)
            delay(GAP_MILLIS)
            firstObserver.cancel()
            delay(SESSION_STOPPED)
            repository.traffic.launchIn(backgroundScope)

            delay(GAP_MILLIS)

            warnings shouldBeEqualTo 2
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the socket keeps failing WHEN it is reopened several times THEN the failure is logged once`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { transport.port } returns PORT
            every { transport.datagrams() } answers { failingWith(SocketException()) }
            repository.traffic.launchIn(backgroundScope)

            delay(SEVERAL_ATTEMPTS)

            verifyOnce {
                Log.w(any(), any<String>(), any())
            }
        }
}

private const val PORT = 14550
private const val GAP_MILLIS = 100L
private val DATAGRAM = Datagram(byteArrayOf(0xFD.toByte()), InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560))

private fun receiving(datagrams: Long): LinkTraffic = LinkTraffic.Receiving(port = PORT, datagrams = datagrams)

private fun recovering(datagrams: Long): LinkTraffic = LinkTraffic.Recovering(port = PORT, datagrams = datagrams)

/** Spaced in virtual time so that every count reaches the observer instead of being conflated. */
private fun datagramsArriving(count: Int): Flow<Datagram> = flow {
    repeat(count) {
        delay(GAP_MILLIS)
        emit(DATAGRAM)
    }
    // The socket stays open with nothing more arriving; a flow that ends would be reopened (REQ-019).
    awaitCancellation()
}

/**
 * Five datagrams 3 s apart, then open with nothing more: the fifth comes 12 s after the first, past the stable
 * time, and the fourth 9 s after it, short of it.
 */
private fun stableDatagrams(): Flow<Datagram> = flow {
    repeat(STABLE_DATAGRAMS) {
        delay(STABLE_PERIOD_MILLIS)
        emit(DATAGRAM)
    }
    awaitCancellation()
}

private const val STABLE_DATAGRAMS = 5
private const val STABLE_PERIOD_MILLIS = 3_000L

/** One datagram and then a failure, as a flapping socket. */
private fun datagramThenFailure(): Flow<Datagram> = flow {
    delay(GAP_MILLIS)
    emit(DATAGRAM)
    throw SocketException()
}

private const val LONG_WAIT = 60_000L

/** Attempts at 0, 1, 3, 7 and 12 s. */
private const val SEVERAL_ATTEMPTS = 12_500L

/** Longer than both stop timeouts (traffic and session): the next observer starts a new session. */
private const val SESSION_STOPPED = 15_000L

private fun failingWith(failure: Throwable): Flow<Datagram> = flow { throw failure }
