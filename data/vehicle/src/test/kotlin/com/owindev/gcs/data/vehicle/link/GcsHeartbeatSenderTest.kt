package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.coInvoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The sender in virtual time with a mocked transport. Sent frames are checked through the codec's parser
 * and decoder, and byte by byte against Mission Planner's real HEARTBEAT (`gcs/heartbeat-mission-planner.bin`),
 * whose fields this GCS copies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-021")
class GcsHeartbeatSenderTest {

    private val transport: UdpTransport = mockk()
    private val sent = mutableListOf<ByteArray>()
    private var results = mutableListOf<SendResult>()

    private lateinit var sender: GcsHeartbeatSender

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), PEER) } coAnswers {
            sent += firstArg<ByteArray>()
            results.removeFirstOrNull() ?: SendResult.Sent
        }
        sender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder()))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN a destination WHEN running THEN sends a GCS HEARTBEAT once per second with increasing seq`() = runTest {
        backgroundScope.launch { sender.run { PEER } }

        delay(TWO_AND_A_HALF_SECONDS)

        sent.map(::decoded) shouldBeEqualTo listOf(0, 1, 2).map { sequence -> gcsHeartbeat(sequence) }
    }

    @Test
    fun `GIVEN 44 HEARTBEATs already sent WHEN the next one goes out THEN it is Mission Planner's real frame`() =
        runTest {
            backgroundScope.launch { sender.run { PEER } }

            delay(UNTIL_SEQUENCE_44)

            sent[MISSION_PLANNER_SEQUENCE] shouldBeEqualTo readFixture(MISSION_PLANNER)
        }

    @Test
    fun `GIVEN no destination WHEN running THEN nothing is sent`() = runTest {
        backgroundScope.launch { sender.run { null } }

        delay(TWO_AND_A_HALF_SECONDS)

        sent shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN the destination comes and goes WHEN running THEN sends only while there is one`() = runTest {
        var destination: InetSocketAddress? = PEER
        backgroundScope.launch { sender.run { destination } }
        delay(ONE_AND_A_HALF_SECONDS)
        destination = null
        delay(TWO_SECONDS)
        destination = PEER

        delay(ONE_SECOND)

        sent.map { decoded(it).sequence } shouldBeEqualTo listOf(0, 1, 2)
    }

    @Test
    fun `GIVEN the link is closed for a send WHEN the next one goes out THEN it reuses the sequence number`() =
        runTest {
            results = mutableListOf(SendResult.Sent, SendResult.NotOpen)
            backgroundScope.launch { sender.run { PEER } }

            delay(TWO_AND_A_HALF_SECONDS)

            sent.map { decoded(it).sequence } shouldBeEqualTo listOf(0, 1, 1)
        }

    @Test
    fun `GIVEN a send fails WHEN running THEN it keeps sending once per second`() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        results = mutableListOf(SendResult.Failed(IOException()))
        backgroundScope.launch { sender.run { PEER } }

        delay(TWO_AND_A_HALF_SECONDS)

        sent.map { decoded(it).sequence } shouldBeEqualTo listOf(0, 0, 1)
    }

    @Test
    fun `GIVEN sends keep failing WHEN running THEN the failure is logged once, without the peer's address`() =
        runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>(), any()) } returns 0
            results = mutableListOf(SendResult.Failed(IOException()), SendResult.Failed(IOException()))
            backgroundScope.launch { sender.run { PEER } }

            delay(TWO_AND_A_HALF_SECONDS)

            verifyOnce {
                Log.w(TAG, "Sending the GCS HEARTBEAT failed", any())
            }
        }

    @Test
    fun `GIVEN the sender is already running WHEN it is started again THEN it throws IllegalStateException`() =
        runTest {
            backgroundScope.launch { sender.run { PEER } }
            runCurrent()

            val result = coInvoking { sender.run { PEER } }

            result shouldThrow IllegalStateException::class
        }

    @Test
    fun `GIVEN 256 HEARTBEATs sent WHEN the next one goes out THEN the sequence number wraps to 0`() = runTest {
        backgroundScope.launch { sender.run { PEER } }

        delay(UNTIL_SEQUENCE_WRAPS)

        sent.takeLast(2).map { decoded(it).sequence } shouldBeEqualTo listOf(LAST_SEQUENCE, 0)
    }

    /** One sent datagram as the codec reads it back. */
    private data class Decoded(val sequence: Int, val systemId: Int, val componentId: Int, val message: MavlinkMessage)

    private fun decoded(datagram: ByteArray): Decoded {
        val frame = MavlinkParser(MavlinkMessages).parse(datagram).single()
        return Decoded(frame.sequence, frame.systemId, frame.componentId, MavlinkDecoder.decode(frame))
    }

    private fun gcsHeartbeat(sequence: Int) = Decoded(
        sequence = sequence,
        systemId = MISSION_PLANNER_SYSTEM_ID,
        componentId = MISSION_PLANNER_COMPONENT_ID,
        message = MavlinkMessage.Heartbeat(
            type = MAV_TYPE_GCS,
            autopilot = MAV_AUTOPILOT_INVALID,
            baseMode = 0,
            customMode = 0,
            systemStatus = 0,
            mavlinkVersion = MAVLINK_VERSION,
        ),
    )

    private companion object {
        const val MISSION_PLANNER = "gcs/heartbeat-mission-planner.bin"
        const val TAG = "GcsHeartbeatSender"

        // Mission Planner's HEARTBEAT as pymavlink decodes it (core/mavlink/src/test/resources/README.md).
        const val MISSION_PLANNER_SEQUENCE = 44
        const val MISSION_PLANNER_SYSTEM_ID = 255
        const val MISSION_PLANNER_COMPONENT_ID = 190
        const val MAV_TYPE_GCS = 6
        const val MAV_AUTOPILOT_INVALID = 8
        const val MAVLINK_VERSION = 3
        const val LAST_SEQUENCE = 255

        // Virtual milliseconds; the sender ticks at 0 s, 1 s, 2 s…
        const val ONE_SECOND = 1_000L
        const val ONE_AND_A_HALF_SECONDS = 1_500L
        const val TWO_SECONDS = 2_000L
        const val TWO_AND_A_HALF_SECONDS = 2_500L
        const val UNTIL_SEQUENCE_44 = 44_500L
        const val UNTIL_SEQUENCE_WRAPS = 256_500L

        val PEER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
    }
}
