package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * One sequence number for every message of this GCS's component, read back from the sent frames with the
 * codec's parser.
 */
@Tag("REQ-021")
class GcsMessageSenderTest {

    private val transport: UdpTransport = mockk()
    private val sent = mutableListOf<ByteArray>()
    private var results = mutableListOf<SendResult>()

    private lateinit var sender: GcsMessageSender

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), PEER) } coAnswers {
            sent += firstArg<ByteArray>()
            results.removeFirstOrNull() ?: SendResult.Sent
        }
        sender = GcsMessageSender(transport)
    }

    @Test
    fun `GIVEN HEARTBEATs and COMMAND_LONGs WHEN sending them in turn THEN they share one sequence number`() = runTest {
        sender.send(HEARTBEAT, PEER)
        sender.send(COMMAND, PEER)
        sender.send(HEARTBEAT, PEER)

        val result = sender.send(COMMAND, PEER)

        result shouldBeEqualTo SendResult.Sent
        sent.map(::headerOf) shouldBeEqualTo listOf(
            Header(sequence = 0, messageId = HEARTBEAT_ID),
            Header(sequence = 1, messageId = COMMAND_LONG_ID),
            Header(sequence = 2, messageId = HEARTBEAT_ID),
            Header(sequence = 3, messageId = COMMAND_LONG_ID),
        )
    }

    @Test
    fun `GIVEN a closed link and then a failure WHEN sending THEN the sequence number only advances once sent`() =
        runTest {
            results = mutableListOf(SendResult.NotOpen, SendResult.Failed(IOException()))
            sender.send(HEARTBEAT, PEER)
            sender.send(COMMAND, PEER)

            val result = sender.send(COMMAND, PEER)

            result shouldBeEqualTo SendResult.Sent
            sent.map { headerOf(it).sequence } shouldBeEqualTo listOf(0, 0, 0)
        }

    @Test
    fun `GIVEN many senders at once WHEN they all send THEN no sequence number is used twice`() = runTest {
        coEvery { transport.send(any(), PEER) } coAnswers {
            // A send that suspends, as a real socket write on the IO dispatcher does, so the others run meanwhile.
            delay(SEND_TIME)
            sent += firstArg<ByteArray>()
            SendResult.Sent
        }
        val senders = List(SENDS_AT_ONCE) { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                if (index % 2 == 0) sender.send(HEARTBEAT, PEER) else sender.send(COMMAND, PEER)
            }
        }

        val result = senders.awaitAll()

        result.toSet() shouldBeEqualTo setOf(SendResult.Sent)
        sent.map { headerOf(it).sequence }.sorted() shouldBeEqualTo (0 until SENDS_AT_ONCE).toList()
    }

    @Test
    fun `GIVEN 256 messages sent WHEN the next one goes out THEN the sequence number wraps to 0`() = runTest {
        repeat(SEQUENCE_NUMBERS) { sender.send(HEARTBEAT, PEER) }

        val result = sender.send(COMMAND, PEER)

        result shouldBeEqualTo SendResult.Sent
        sent.takeLast(2).map { headerOf(it).sequence } shouldBeEqualTo listOf(LAST_SEQUENCE, 0)
    }

    /** The header fields this test checks, as the codec's parser reads them. */
    private data class Header(val sequence: Int, val messageId: Int)

    private fun headerOf(datagram: ByteArray): Header {
        val frame = MavlinkParser(MavlinkMessages).parse(datagram).single()
        return Header(frame.sequence, frame.messageId)
    }

    private companion object {
        const val HEARTBEAT_ID = 0
        const val COMMAND_LONG_ID = 76
        const val SENDS_AT_ONCE = 10
        const val SEND_TIME = 10L
        const val SEQUENCE_NUMBERS = 256
        const val LAST_SEQUENCE = 255

        val PEER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val HEARTBEAT = GcsHeartbeatSender.GCS_HEARTBEAT
        val COMMAND = CommandLong(
            targetSystem = 1,
            targetComponent = 1,
            command = 511,
            confirmation = 0,
            param1 = 30f,
            param2 = 100_000f,
            param3 = 0f,
            param4 = 0f,
            param5 = 0f,
            param6 = 0f,
            param7 = 0f,
        )
    }
}
