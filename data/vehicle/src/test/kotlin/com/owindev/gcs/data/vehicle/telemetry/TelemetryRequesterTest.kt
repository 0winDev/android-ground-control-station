package com.owindev.gcs.data.vehicle.telemetry

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The requester in virtual time, with a mocked sender and a hand-fed link: the "vehicle" answers each
 * COMMAND_LONG by putting a COMMAND_ACK on the session's messages, as ArduCopter did in the SITL session
 * (`command/set-message-interval-ack.bin`: command 511, ACCEPTED, from 1/1 to 255/190).
 */
@Tag("REQ-023")
class TelemetryRequesterTest {

    private val sender: GcsMessageSender = mockk()
    private val session: LinkSession = mockk()
    private val messages = MutableSharedFlow<ReceivedMessage>()
    private val sent = mutableListOf<CommandLong>()

    private lateinit var requester: TelemetryRequester

    @BeforeEach
    fun setUp() {
        every { session.messages } returns messages
        requester = TelemetryRequester(sender, session)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN the vehicle accepts every request WHEN requesting THEN each stream is asked for once, in order`() =
        runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }

            val result = requester.requestAll(TARGET)

            result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
            sent shouldBeEqualTo listOf(
                setMessageInterval(messageId = 30, intervalMicros = 100_000f),
                setMessageInterval(messageId = 33, intervalMicros = 200_000f),
                setMessageInterval(messageId = 74, intervalMicros = 250_000f),
                setMessageInterval(messageId = 24, intervalMicros = 500_000f),
                setMessageInterval(messageId = 1, intervalMicros = 1_000_000f),
            )
        }

    @Test
    fun `GIVEN the vehicle denies one request WHEN requesting THEN only that stream is rejected`() = runTest {
        mockLog()
        vehicleAnswers { command ->
            ack(if (command.param1 == VFR_HUD_ID) MAV_RESULT_DENIED else MAV_RESULT_ACCEPTED)
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo listOf(
            StreamResult.Accepted,
            StreamResult.Accepted,
            StreamResult.Rejected(MAV_RESULT_DENIED),
            StreamResult.Accepted,
            StreamResult.Accepted,
        )
    }

    @Test
    fun `GIVEN the vehicle never answers WHEN requesting THEN every stream has no ACK after its timeout`() = runTest {
        mockLog()
        vehicleAnswers { null }
        val start = testScheduler.timeSource.markNow()

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.NoAck }
        start.elapsedNow().inWholeMilliseconds shouldBeEqualTo STREAMS * ACK_TIMEOUT_MILLIS
    }

    @Test
    fun `GIVEN an IN_PROGRESS ACK before the final one WHEN requesting THEN the final result is kept`() = runTest {
        coEvery { sender.send(any<CommandLong>(), VEHICLE_ADDRESS) } coAnswers {
            sent += firstArg<CommandLong>()
            answerLater(ack(MAV_RESULT_IN_PROGRESS), ack(MAV_RESULT_ACCEPTED))
            SendResult.Sent
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
    }

    @ParameterizedTest(name = "from {0}/{1}, command {2}, to {3}/{4}")
    @CsvSource(
        "2, 1, 511, 255, 190",
        "1, 154, 511, 255, 190",
        "1, 1, 512, 255, 190",
        "1, 1, 511, 254, 190",
        "1, 1, 511, 255, 191",
    )
    fun `GIVEN an ACK that is not the answer WHEN requesting THEN it is ignored`(
        systemId: Int,
        componentId: Int,
        command: Int,
        targetSystem: Int,
        targetComponent: Int,
    ) = runTest {
        mockLog()
        vehicleAnswers {
            ReceivedMessage(
                systemId = systemId,
                componentId = componentId,
                message = CommandAck(
                    command = command,
                    result = MAV_RESULT_ACCEPTED,
                    progress = 0,
                    resultParam2 = 0,
                    targetSystem = targetSystem,
                    targetComponent = targetComponent,
                ),
                sender = VEHICLE_ADDRESS,
            )
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.NoAck }
    }

    @Test
    fun `GIVEN ACKs without extensions WHEN requesting THEN their zero targets are accepted as the answer`() = runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED, targetSystem = 0, targetComponent = 0) }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
    }

    @Test
    fun `GIVEN the link is closed and then a send fails WHEN requesting THEN those streams are not sent`() = runTest {
        mockLog()
        val sendResults = mutableListOf(SendResult.NotOpen, SendResult.Failed(IOException()))
        coEvery { sender.send(any<CommandLong>(), VEHICLE_ADDRESS) } coAnswers {
            sent += firstArg<CommandLong>()
            sendResults.removeFirstOrNull() ?: SendResult.Sent.also { answerLater(ack(MAV_RESULT_ACCEPTED)) }
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo listOf(
            StreamResult.NotSent,
            StreamResult.NotSent,
            StreamResult.Accepted,
            StreamResult.Accepted,
            StreamResult.Accepted,
        )
    }

    @Test
    fun `GIVEN rounds that fail the same way and then recover WHEN requesting THEN each change is logged once`() =
        runTest {
            mockLog()
            vehicleAnswers { null }
            requester.requestAll(TARGET)
            requester.requestAll(TARGET)
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }

            val result = requester.requestAll(TARGET)

            result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
            verifyOnce {
                Log.w(TAG, "Telemetry stream 30 not set: NoAck")
                Log.w(TAG, "Telemetry stream 33 not set: NoAck")
                Log.w(TAG, "Telemetry stream 74 not set: NoAck")
                Log.w(TAG, "Telemetry stream 24 not set: NoAck")
                Log.w(TAG, "Telemetry stream 1 not set: NoAck")
                Log.i(TAG, "Telemetry stream 30 accepted again")
                Log.i(TAG, "Telemetry stream 33 accepted again")
                Log.i(TAG, "Telemetry stream 74 accepted again")
                Log.i(TAG, "Telemetry stream 24 accepted again")
                Log.i(TAG, "Telemetry stream 1 accepted again")
            }
        }

    @Test
    fun `GIVEN every stream accepted from the start WHEN requesting twice THEN nothing is logged`() = runTest {
        mockLog()
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
        requester.requestAll(TARGET)

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
        verifyNever {
            Log.w(any(), any<String>())
            Log.i(any(), any<String>())
        }
    }

    @Test
    fun `GIVEN the vehicle connects, is lost and connects again WHEN running THEN every stream is requested twice`() =
        runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
            val targets = flow {
                emit(TARGET)
                delay(ONE_MINUTE)
                emit(null)
                delay(ONE_MINUTE)
                emit(TARGET)
            }

            requester.run(targets)

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + STREAM_IDS
        }

    @Test
    fun `GIVEN the vehicle is lost during a round WHEN running THEN the rest of the round is not requested`() =
        runTest {
            mockLog()
            vehicleAnswers { null }
            val targets = flow {
                emit(TARGET)
                delay(ONE_AND_A_HALF_TIMEOUTS)
                emit(null)
            }

            requester.run(targets)

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS.take(2)
        }

    /** The vehicle answers each COMMAND_LONG sent with [answer], or not at all when it gives `null`. */
    private fun TestScope.vehicleAnswers(answer: (CommandLong) -> ReceivedMessage?) {
        coEvery { sender.send(any<CommandLong>(), VEHICLE_ADDRESS) } coAnswers {
            val command = firstArg<CommandLong>()
            sent += command
            answer(command)?.let { answerLater(it) }
            SendResult.Sent
        }
    }

    /** Puts [answers] on the link after the send returns, as a real vehicle's reply arrives later. */
    private fun TestScope.answerLater(vararg answers: ReceivedMessage) {
        backgroundScope.launch {
            delay(ANSWER_DELAY)
            answers.forEach { messages.emit(it) }
        }
    }

    private fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
    }

    private companion object {
        const val TAG = "TelemetryRequester"
        const val VEHICLE_SYSTEM_ID = 1
        const val AUTOPILOT_COMPONENT_ID = 1
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val STREAMS = 5
        const val VFR_HUD_ID = 74f
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_DENIED = 2
        const val MAV_RESULT_IN_PROGRESS = 5
        const val ACK_TIMEOUT_MILLIS = 1_000L
        const val ANSWER_DELAY = 50L
        const val ONE_MINUTE = 60_000L

        // The first request times out at 1 s, so the second one is in flight when the vehicle is lost.
        const val ONE_AND_A_HALF_TIMEOUTS = 1_500L

        val STREAM_IDS = listOf(30f, 33f, 74f, 24f, 1f)
        val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val TARGET = TelemetryTarget(SystemId(VEHICLE_SYSTEM_ID), VEHICLE_ADDRESS)

        fun ack(result: Int, targetSystem: Int = GCS_SYSTEM_ID, targetComponent: Int = GCS_COMPONENT_ID) =
            ReceivedMessage(
                systemId = VEHICLE_SYSTEM_ID,
                componentId = AUTOPILOT_COMPONENT_ID,
                message = CommandAck(
                    command = MAV_CMD_SET_MESSAGE_INTERVAL,
                    result = result,
                    progress = 0,
                    resultParam2 = 0,
                    targetSystem = targetSystem,
                    targetComponent = targetComponent,
                ),
                sender = VEHICLE_ADDRESS,
            )

        fun setMessageInterval(messageId: Int, intervalMicros: Float) = CommandLong(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = AUTOPILOT_COMPONENT_ID,
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            confirmation = 0,
            param1 = messageId.toFloat(),
            param2 = intervalMicros,
            param3 = 0f,
            param4 = 0f,
            param5 = 0f,
            param6 = 0f,
            param7 = 0f,
        )
    }
}
