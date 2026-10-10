package com.owindev.gcs.core.transport

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Receives the UDP datagrams sent to [port] (on every local interface by default).
 *
 * The socket only exists while [datagrams] is being collected: it is bound when collection starts and
 * closed when the collector is cancelled.
 *
 * @param port the local UDP port to listen on; ArduPilot SITL and companion links use [DEFAULT_PORT].
 * @param ioDispatcher where the blocking `receive()` loop runs.
 * @param socketFactory binds a socket to the given port; tests inject one bound to an ephemeral port.
 */
class UdpTransport(
    val port: Int = DEFAULT_PORT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: (Int) -> DatagramSocket = ::DatagramSocket,
) {

    /**
     * A cold flow of the payload of every datagram received, in arrival order. Each collection binds
     * its own socket, so share it instead of collecting it twice: a second bind to the same port fails.
     *
     * Backpressure: datagrams are handed over with a suspending `send`, never dropped by the flow. If
     * the collector is slow, the receive loop suspends and the burst waits in the operating system's
     * socket buffer; whatever does not fit there is dropped by the kernel, as UDP allows.
     *
     * Fails with [java.net.BindException] when the port cannot be bound (e.g. it is in use), or with
     * another [IOException] if receiving fails while the socket is open. Cancelling the collector is
     * not a failure: the flow just stops.
     */
    fun datagrams(): Flow<ByteArray> = callbackFlow {
        val socket = socketFactory(port)
        launch(ioDispatcher) { receiveInto(socket) }
        // A blocked receive() ignores coroutine cancellation; closing the socket is what unblocks it.
        awaitClose { socket.close() }
    }

    private suspend fun ProducerScope<ByteArray>.receiveInto(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                // receive() shrinks the packet length to the last datagram; without this reset a
                // longer datagram that follows a short one would be truncated.
                packet.length = buffer.size
                socket.receive(packet)
                // The copy is unavoidable: the buffer is reused while the collector may still hold the bytes.
                send(buffer.copyOf(packet.length))
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
