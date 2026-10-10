package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.UdpTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.BindException
import java.net.SocketException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        repository = DefaultLinkRepository(transport = transport, scope = testScope.backgroundScope)
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
}

private const val PORT = 14550
private const val GAP_MILLIS = 100L
private val DATAGRAM = byteArrayOf(0xFD.toByte())

private fun receiving(datagrams: Long): LinkTraffic = LinkTraffic.Receiving(port = PORT, datagrams = datagrams)

/** Spaced in virtual time so that every count reaches the observer instead of being conflated. */
private fun datagramsArriving(count: Int): Flow<ByteArray> = flow {
    repeat(count) {
        delay(GAP_MILLIS)
        emit(DATAGRAM)
    }
}

private fun failingWith(failure: Throwable): Flow<ByteArray> = flow { throw failure }
