package com.owindev.gcs.core.transport

import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

        result.payload shouldBeEqualTo FIRST_PAYLOAD
    }

    @Test
    fun `GIVEN several datagrams sent to the port WHEN collecting THEN emits each one in arrival order`() = runTest {
        val payloads = listOf(FIRST_PAYLOAD, SECOND_PAYLOAD, THIRD_PAYLOAD)
        payloads.forEach(::sendToTransport)

        val result = transport.datagrams().take(payloads.size).toList()

        result.map { it.payload.toList() } shouldBeEqualTo payloads.map(ByteArray::toList)
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

    @Tag("REQ-021")
    @Test
    fun `GIVEN a datagram from a peer WHEN collecting THEN it carries the peer's address`() = runTest {
        DatagramSocket(EPHEMERAL_PORT, loopback).use { peer ->
            peer.send(DatagramPacket(FIRST_PAYLOAD, FIRST_PAYLOAD.size, loopback, socket.localPort))

            val result = transport.datagrams().first()

            result.sender shouldBeEqualTo InetSocketAddress(loopback, peer.localPort)
        }
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the link is open WHEN sending to the peer THEN it gets the bytes from the transport's port`() = runTest {
        DatagramSocket(EPHEMERAL_PORT, loopback).use { peer ->
            peer.soTimeout = RECEIVE_TIMEOUT_MS
            backgroundScope.launch { transport.datagrams().collect {} }
            runCurrent()

            val result = transport.send(FIRST_PAYLOAD, InetSocketAddress(loopback, peer.localPort))
            val received = peer.receiveOne()

            result shouldBeEqualTo SendResult.Sent
            received.data.copyOf(received.length) shouldBeEqualTo FIRST_PAYLOAD
            received.port shouldBeEqualTo socket.localPort
        }
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN nobody collects the datagrams WHEN sending THEN nothing is sent because the link is closed`() = runTest {
        val peer = InetSocketAddress(loopback, socket.localPort)

        val result = transport.send(FIRST_PAYLOAD, peer)

        result shouldBeEqualTo SendResult.NotOpen
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the collector was cancelled WHEN sending THEN nothing is sent because the link is closed`() = runTest {
        val peer = InetSocketAddress(loopback, socket.localPort)
        val collector = launch { transport.datagrams().collect {} }
        runCurrent()
        collector.cancelAndJoin()

        val result = transport.send(FIRST_PAYLOAD, peer)

        result shouldBeEqualTo SendResult.NotOpen
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the operating system rejects the datagram WHEN sending THEN it is Failed, not thrown`() = runTest {
        val rejecting = RejectingSocket(loopback)
        val rejectingTransport = UdpTransport(port = rejecting.localPort, socketFactory = { rejecting })
        backgroundScope.launch { rejectingTransport.datagrams().collect {} }
        runCurrent()

        val result = rejectingTransport.send(FIRST_PAYLOAD, InetSocketAddress(loopback, socket.localPort))

        result shouldBeInstanceOf SendResult.Failed::class
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the socket closes during a send WHEN sending THEN it is NotOpen, not a failure`() = runTest {
        val closing = RejectingSocket(loopback, closeFirst = true)
        val closingTransport = UdpTransport(port = closing.localPort, socketFactory = { closing })
        backgroundScope.launch { closingTransport.datagrams().collect {} }
        runCurrent()

        val result = closingTransport.send(FIRST_PAYLOAD, InetSocketAddress(loopback, socket.localPort))

        result shouldBeEqualTo SendResult.NotOpen
    }

    /** A real socket whose `send` always throws, optionally closing itself first as if the link closed. */
    private class RejectingSocket(address: InetAddress, private val closeFirst: Boolean = false) :
        DatagramSocket(EPHEMERAL_PORT, address) {

        override fun send(packet: DatagramPacket) {
            if (closeFirst) close()
            throw IOException("Rejected by the test")
        }
    }

    private fun DatagramSocket.receiveOne(): DatagramPacket =
        DatagramPacket(ByteArray(MAX_TEST_DATAGRAM), MAX_TEST_DATAGRAM).also(::receive)

    private fun sendToTransport(payload: ByteArray) {
        DatagramSocket().use { sender ->
            sender.send(DatagramPacket(payload, payload.size, loopback, socket.localPort))
        }
    }

    private companion object {
        const val EPHEMERAL_PORT = 0
        const val RECEIVE_TIMEOUT_MS = 5_000
        const val MAX_TEST_DATAGRAM = 64
        val FIRST_PAYLOAD = byteArrayOf(0xFD.toByte(), 0x09, 0x00)
        val SECOND_PAYLOAD = byteArrayOf(0x01, 0x02)
        val THIRD_PAYLOAD = byteArrayOf(0x7F)
    }
}
