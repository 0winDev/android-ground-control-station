package com.owindev.gcs.core.transport

import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.coInvoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real sockets on the loopback interface. The receiving socket is bound to an ephemeral port before
 * each test, so datagrams sent before collecting wait in its OS buffer and the tests never race the bind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-005")
class UdpTransportTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private lateinit var socket: DatagramSocket

    private lateinit var transport: UdpTransport

    @BeforeEach
    fun setUp() {
        socket = DatagramSocket(EPHEMERAL_PORT, loopback)
        transport = UdpTransport(port = socket.localPort, socketFactory = { socket })
    }

    @AfterEach
    fun tearDown() {
        socket.close()
    }

    @Test
    fun `GIVEN a datagram sent to the port WHEN collecting THEN emits its bytes`() = runTest {
        sendToTransport(FIRST_PAYLOAD)

        val result = transport.datagrams().first()

        result shouldBeEqualTo FIRST_PAYLOAD
    }

    @Test
    fun `GIVEN several datagrams sent to the port WHEN collecting THEN emits each one in arrival order`() = runTest {
        val payloads = listOf(FIRST_PAYLOAD, SECOND_PAYLOAD, THIRD_PAYLOAD)
        payloads.forEach(::sendToTransport)

        val result = transport.datagrams().take(payloads.size).toList()

        result.map(ByteArray::toList) shouldBeEqualTo payloads.map(ByteArray::toList)
    }

    @Test
    fun `GIVEN a collector WHEN it is cancelled THEN the socket is closed`() = runTest {
        val collector = launch { transport.datagrams().collect {} }
        runCurrent()

        collector.cancelAndJoin()
        val result = socket.isClosed

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a collector WHEN it is cancelled THEN it completes with the cancellation and no failure`() = runTest {
        val completion = CompletableDeferred<Throwable?>()
        val collector = launch { transport.datagrams().collect {} }
        collector.invokeOnCompletion { cause -> completion.complete(cause) }
        runCurrent()

        collector.cancelAndJoin()
        val result = completion.await()

        result shouldBeInstanceOf CancellationException::class
    }

    @Test
    fun `GIVEN the port is already bound by another socket WHEN collecting THEN fails with BindException`() = runTest {
        val busyTransport = UdpTransport(
            port = socket.localPort,
            socketFactory = { port -> DatagramSocket(port, loopback) },
        )

        val result = coInvoking { busyTransport.datagrams().first() }

        result shouldThrow BindException::class
    }

    private fun sendToTransport(payload: ByteArray) {
        DatagramSocket().use { sender ->
            sender.send(DatagramPacket(payload, payload.size, loopback, socket.localPort))
        }
    }

    private companion object {
        const val EPHEMERAL_PORT = 0
        val FIRST_PAYLOAD = byteArrayOf(0xFD.toByte(), 0x09, 0x00)
        val SECOND_PAYLOAD = byteArrayOf(0x01, 0x02)
        val THIRD_PAYLOAD = byteArrayOf(0x7F)
    }
}
