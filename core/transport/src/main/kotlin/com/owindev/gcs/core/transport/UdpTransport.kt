package com.owindev.gcs.core.transport

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives the UDP datagrams sent to [port] (on every local interface by default), and sends datagrams
 * back from that same port.
 *
 * The socket only exists while [datagrams] is being collected: it is bound when collection starts and
 * closed when the collector is cancelled. [send] uses that socket, so nothing can be sent while the link
 * is closed, and a peer sees the replies coming from the port it sends to.
 *
 * @param port the local UDP port to listen on; ArduPilot SITL and companion links use [DEFAULT_PORT].
 * @param ioDispatcher where the blocking `receive()` loop and `send()` run.
 * @param socketFactory binds a socket to the given port; tests inject one bound to an ephemeral port.
 */
class UdpTransport(
    val port: Int = DEFAULT_PORT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: (Int) -> DatagramSocket = ::DatagramSocket,
) : DatagramSource {

    /** The socket of the current collection of [datagrams], `null` while nobody collects it. */
    private val openSocket = AtomicReference<DatagramSocket?>(null)

    /**
     * A cold flow of every datagram received, in arrival order, with the address it came from. Each
     * collection binds its own socket, so share it instead of collecting it twice: a second bind to the
     * same port fails.
     *
     * Backpressure: datagrams are handed over with a suspending `send`, never dropped by the flow. If
     * the collector is slow, the receive loop suspends and the burst waits in the operating system's
     * socket buffer; whatever does not fit there is dropped by the kernel, as UDP allows.
     *
     * Fails with [java.net.BindException] when the port cannot be bound (e.g. it is in use), or with
     * another [IOException] if receiving fails while the socket is open. Cancelling the collector is
     * not a failure: the flow just stops.
     */
    override fun datagrams(): Flow<Datagram> = callbackFlow {
        val socket = socketFactory(port)
        openSocket.set(socket)
        launch(ioDispatcher) { receiveInto(socket) }
        // A blocked receive() ignores coroutine cancellation; closing the socket is what unblocks it.
        awaitClose {
            openSocket.compareAndSet(socket, null)
            socket.close()
        }
    }

    /**
     * Sends [payload] as one datagram to [to], from the socket [datagrams] is receiving on.
     *
     * Never throws on I/O: [SendResult.NotOpen] when no collection holds the socket (the link is closed),
     * [SendResult.Failed] when the operating system rejects the datagram or [to] is not a resolved address, which no
     * datagram can be sent to.
     */
    suspend fun send(payload: ByteArray, to: InetSocketAddress): SendResult = withContext(ioDispatcher) {
        if (to.isUnresolved) return@withContext SendResult.Failed(UnknownHostException("Unresolved destination"))
        val socket = openSocket.get() ?: return@withContext SendResult.NotOpen
        try {
            socket.send(DatagramPacket(payload, payload.size, to))
            SendResult.Sent
        } catch (e: IOException) {
            // Closed between reading it and sending: the link closed, which is not a send failure.
            if (socket.isClosed) SendResult.NotOpen else SendResult.Failed(e)
        }
    }

    private suspend fun ProducerScope<Datagram>.receiveInto(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                // receive() shrinks the packet length to the last datagram; without this reset a
                // longer datagram that follows a short one would be truncated.
                packet.length = buffer.size
                socket.receive(packet)
                // The copy is unavoidable: the buffer is reused while the collector may still hold the bytes.
                send(Datagram(buffer.copyOf(packet.length), packet.socketAddress as InetSocketAddress))
            }
        } catch (e: IOException) {
            // A closed socket means the collector was cancelled (awaitClose closed it): stop quietly.
            if (!socket.isClosed) {
                close(e)
            }
        }
    }

    companion object {
        /** The UDP port ground stations conventionally listen on for MAVLink. */
        const val DEFAULT_PORT = 14550

        /** Largest UDP payload over IPv4 (65,535 − 8-byte UDP header − 20-byte IP header). */
        private const val MAX_DATAGRAM_SIZE = 65_507
    }
}

/**
 * One received datagram: its [payload] and the [sender] it came from, which is where replies go.
 *
 * Not a data class: it holds an array, so equality compares its contents explicitly.
 */
class Datagram(val payload: ByteArray, val sender: InetSocketAddress) {

    override fun equals(other: Any?): Boolean =
        other is Datagram && payload.contentEquals(other.payload) && sender == other.sender

    override fun hashCode(): Int = HASH_MULTIPLIER * payload.contentHashCode() + sender.hashCode()

    override fun toString(): String = "Datagram(${payload.size} bytes from $sender)"

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

/** What happened to a datagram given to [UdpTransport.send]. */
sealed interface SendResult {

    data object Sent : SendResult

    /** The link is closed: nobody collects [UdpTransport.datagrams], so there is no socket to send from. */
    data object NotOpen : SendResult

    /** The operating system rejected the datagram (e.g. the network is unreachable). */
    data class Failed(val cause: IOException) : SendResult
}
