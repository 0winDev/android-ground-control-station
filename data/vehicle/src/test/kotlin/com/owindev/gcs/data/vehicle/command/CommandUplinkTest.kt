package com.owindev.gcs.data.vehicle.command

import android.util.Log
import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * What a command tells of the uplink, in virtual time with a mocked sender and a hand-fed link: the "vehicle" answers
 * from its autopilot (1/1) at the pinned address to this GCS (255/190), or stays silent. An arm waits 1.5 s and then
 * listens 10 s for a late answer; an RTL tries three times, 4.5 s in all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-045")
class CommandUplinkTest {

    private val testScope = TestScope()
    private val sender: GcsMessageSender = mockk()
    private val session: LinkSession = mockk()
    private val messages = MutableSharedFlow<ReceivedMessage>()
    private val sessions = MutableStateFlow(FIRST_SESSION)
    private val uplink = DefaultUplinkRepository(testScope.testScheduler.timeSource)
    private var sendResults = mutableListOf<CommandSendResult>()

    private lateinit var exchange: CommandExchange

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { session.messages } returns messages
        every { session.session } returns sessions
        coEvery { sender.send(any<CommandLong>(), any<() -> CommandRoute>()) } answers {
            sendResults.removeFirstOrNull() ?: CommandSendResult.Sent
        }
        exchange = CommandExchange(sender, session, uplink) { CommandRoute.Open(VEHICLE_ADDRESS) }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN a silent vehicle WHEN arming THEN the command is unanswered since its timeout`() = testScope.runTest {
        sendInBackground(ARM_COMMAND)

        delay(WITHIN_ARM_LATE_WINDOW)

        uplink.health.value.unansweredSince?.elapsedNow() shouldBeEqualTo SINCE_ARM_TIMEOUT
    }

    @Test
    fun `GIVEN an arm without answer WHEN HEARTBEATs keep being sent THEN the command stays unanswered`() =
        testScope.runTest {
            sendInBackground(ARM_COMMAND)
            delay(WITHIN_ARM_LATE_WINDOW)

            repeat(HEARTBEAT_PERIODS) { uplink.heartbeatSent() }

            uplink.health.value.unansweredSince?.elapsedNow() shouldBeEqualTo SINCE_ARM_TIMEOUT
        }

    @Test
    fun `GIVEN an arm without answer WHEN the next command is rejected THEN the uplink is healthy`() =
        testScope.runTest {
            exchange.run(SYSTEM, LABEL, ARM_COMMAND, onState = {}) {}
            answerLater(ack(MAV_RESULT_DENIED, MAV_CMD_NAV_RETURN_TO_LAUNCH), after = ANSWER_DELAY)

            exchange.run(SYSTEM, LABEL, RETURN_TO_LAUNCH_COMMAND, onState = {}) {}

            uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
        }

    @Test
    fun `GIVEN an arm without answer WHEN its late ACK arrives within the window THEN the uplink is healthy`() =
        testScope.runTest {
            answerLater(ack(MAV_RESULT_ACCEPTED, MAV_CMD_COMPONENT_ARM_DISARM), after = WITHIN_ARM_LATE_WINDOW)

            exchange.run(SYSTEM, LABEL, ARM_COMMAND, onState = {}) {}

            uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
        }

    @Test
    fun `GIVEN an arm without answer WHEN its late ACK arrives right after the timeout THEN the uplink is healthy`() =
        testScope.runTest {
            answerLater(ack(MAV_RESULT_ACCEPTED, MAV_CMD_COMPONENT_ARM_DISARM), after = RIGHT_AFTER_ARM_TIMEOUT)

            exchange.run(SYSTEM, LABEL, ARM_COMMAND, onState = {}) {}

            uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
        }

    @Test
    fun `GIVEN an arm without answer WHEN a late ACK comes from another address THEN the command stays unanswered`() =
        testScope.runTest {
            val stranger = ack(MAV_RESULT_ACCEPTED, MAV_CMD_COMPONENT_ARM_DISARM).copy(sender = OTHER_ADDRESS)
            answerLater(stranger, after = WITHIN_ARM_LATE_WINDOW)

            exchange.run(SYSTEM, LABEL, ARM_COMMAND, onState = {}) {}

            uplink.health.value.unansweredSince?.elapsedNow() shouldBeEqualTo SINCE_ARM_TIMEOUT_AT_ITS_END
        }

    @Test
    fun `GIVEN an answered RTL WHEN it ends THEN the uplink is healthy`() = testScope.runTest {
        answerLater(ack(MAV_RESULT_ACCEPTED, MAV_CMD_NAV_RETURN_TO_LAUNCH), after = ANSWER_DELAY)

        exchange.run(SYSTEM, LABEL, RETURN_TO_LAUNCH_COMMAND, onState = {}) {}

        uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
    }

    @Test
    fun `GIVEN an RTL in progress and then silent WHEN it ends THEN the uplink is healthy`() = testScope.runTest {
        answerLater(ack(MAV_RESULT_IN_PROGRESS, MAV_CMD_NAV_RETURN_TO_LAUNCH), after = ANSWER_DELAY)

        exchange.run(SYSTEM, LABEL, RETURN_TO_LAUNCH_COMMAND, onState = {}) {}

        uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
    }

    @Test
    fun `GIVEN an RTL whose third send fails WHEN it ends THEN the command is unanswered`() = testScope.runTest {
        sendResults = mutableListOf(
            CommandSendResult.Sent,
            CommandSendResult.Sent,
            CommandSendResult.NotSent(NotSentReason.SEND_FAILED),
        )

        exchange.run(SYSTEM, LABEL, RETURN_TO_LAUNCH_COMMAND, onState = {}) {}

        uplink.health.value.unansweredSince?.elapsedNow() shouldBeEqualTo RTL_LATE_WINDOW
    }

    @Test
    fun `GIVEN an RTL whose third send finds the link lost WHEN it ends THEN the uplink is healthy`() =
        testScope.runTest {
            sendResults = mutableListOf(
                CommandSendResult.Sent,
                CommandSendResult.Sent,
                CommandSendResult.NotSent(NotSentReason.LINK_LOST),
            )

            exchange.run(SYSTEM, LABEL, RETURN_TO_LAUNCH_COMMAND, onState = {}) {}

            uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
        }

    private fun TestScope.sendInBackground(command: MavCommand) {
        backgroundScope.launch { exchange.run(SYSTEM, LABEL, command, onState = {}) {} }
    }

    private fun TestScope.answerLater(answer: ReceivedMessage, after: Long) {
        backgroundScope.launch {
            delay(after)
            messages.emit(answer)
        }
    }

    private companion object {
        const val LABEL = "COMMAND"
        const val VEHICLE_SYSTEM_ID = 1
        const val AUTOPILOT_COMPONENT_ID = 1
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val UNKNOWN_PROGRESS = 255
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_DENIED = 2
        const val MAV_RESULT_IN_PROGRESS = 5
        const val HEARTBEAT_PERIODS = 5

        // Virtual milliseconds: an arm's attempt times out at 1.5 s, its late window ends at 11.5 s.
        const val ANSWER_DELAY = 50L
        const val RIGHT_AFTER_ARM_TIMEOUT = 1_501L
        const val WITHIN_ARM_LATE_WINDOW = 5_000L

        val SINCE_ARM_TIMEOUT = 3_500.milliseconds
        val SINCE_ARM_TIMEOUT_AT_ITS_END = 10_000.milliseconds
        val RTL_LATE_WINDOW = 1_500.milliseconds
        val SYSTEM = SystemId(VEHICLE_SYSTEM_ID)
        val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val OTHER_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_570)

        fun ack(result: Int, command: Int) = ReceivedMessage(
            systemId = VEHICLE_SYSTEM_ID,
            componentId = AUTOPILOT_COMPONENT_ID,
            message = CommandAck(command, result, UNKNOWN_PROGRESS, resultParam2 = 0, GCS_SYSTEM_ID, GCS_COMPONENT_ID),
            sender = VEHICLE_ADDRESS,
            session = FIRST_SESSION,
        )
    }
}
