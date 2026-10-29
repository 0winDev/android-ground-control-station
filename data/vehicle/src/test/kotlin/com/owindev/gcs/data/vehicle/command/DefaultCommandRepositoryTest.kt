package com.owindev.gcs.data.vehicle.command

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.Rejection
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * The command service in virtual time, with a mocked sender and a hand-fed link: the "vehicle" answers a COMMAND_LONG
 * by putting a COMMAND_ACK on the session's messages 50 ms later, from its autopilot (1/1) to this GCS (255/190).
 * Command IDs and MAV_RESULT values come from `common.xml` (mavlink/mavlink master). An attempt waits 1.5 s, so an
 * unanswered RTL ends at 4.5 s; the tests act well away from those edges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-042")
class DefaultCommandRepositoryTest {

    private val testScope = TestScope()
    private val sender: GcsMessageSender = mockk()
    private val session: LinkSession = mockk()
    private val messages = MutableSharedFlow<ReceivedMessage>()
    private val sessions = MutableStateFlow(FIRST_SESSION)
    private val sent = mutableListOf<CommandLong>()
    private var route: CommandRoute = OPEN_ROUTE

    private lateinit var repository: DefaultCommandRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { session.messages } returns messages
        every { session.session } returns sessions
        repository = DefaultCommandRepository(CommandExchange(sender, session) { route }, testScope.backgroundScope)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN each command WHEN sent THEN it goes once to the autopilot as common xml defines it`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED, command.command) }

            val result = listOf(repository.arm(SYSTEM), repository.returnToLaunch(SYSTEM), repository.land(SYSTEM))

            result shouldBeEqualTo List(3) { CommandResult.Accepted }
            sent shouldBeEqualTo listOf(
                commandLong(MAV_CMD_COMPONENT_ARM_DISARM, param1 = 1f),
                commandLong(MAV_CMD_NAV_RETURN_TO_LAUNCH),
                commandLong(MAV_CMD_NAV_LAND),
            )
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `GIVEN the vehicle answers only a later attempt WHEN sending an RTL THEN it is re-sent until accepted`(
        answeredConfirmation: Int,
    ) = testScope.runTest {
        vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED).takeIf { command.confirmation == answeredConfirmation } }

        val result = repository.returnToLaunch(SYSTEM)

        result shouldBeEqualTo CommandResult.Accepted
        sent.map { it.confirmation } shouldBeEqualTo (0..answeredConfirmation).toList()
    }

    @Test
    fun `GIVEN the vehicle never answers WHEN sending an RTL THEN three attempts and no answer at 4,5 s`() =
        testScope.runTest {
            vehicleAnswers { null }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 3)
            sent.map { it.confirmation } shouldBeEqualTo listOf(0, 1, 2)
            currentTime shouldBeEqualTo THREE_ATTEMPTS
        }

    @Test
    fun `GIVEN the vehicle never answers WHEN arming THEN it is sent once and never again`() = testScope.runTest {
        vehicleAnswers { null }

        val result = repository.arm(SYSTEM)
        delay(LONG_AFTER)

        result shouldBeEqualTo noAnswer(attempts = 1)
        sent shouldBeEqualTo listOf(commandLong(MAV_CMD_COMPONENT_ARM_DISARM, param1 = 1f))
    }

    @Test
    fun `GIVEN the vehicle denies an RTL WHEN sending it THEN it is rejected and never re-sent`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_DENIED) }

        val result = repository.returnToLaunch(SYSTEM)

        result shouldBeEqualTo CommandResult.Rejected(Rejection.DENIED, reason = null)
        sent.size shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN the vehicle starts and then accepts WHEN sending an RTL THEN pending shows its progress`() =
        testScope.runTest {
            val states = mutableListOf<PendingCommand?>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.pending.toList(states) }
            vehicleAnswers { null }
            answerLater(ack(MAV_RESULT_IN_PROGRESS, progress = 40), after = ANSWER_DELAY)
            answerLater(ack(MAV_RESULT_ACCEPTED), after = PAST_TWO_ATTEMPTS)

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo CommandResult.Accepted
            sent.size shouldBeEqualTo 1
            states shouldBeEqualTo listOf(
                null,
                pendingRtl(PendingState.Waiting(attempt = 1)),
                pendingRtl(PendingState.InProgress(percent = 40)),
                null,
            )
        }

    @Test
    fun `GIVEN the vehicle starts and falls silent WHEN sending an RTL THEN no answer, started by it, at 10 s`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_IN_PROGRESS).takeIf { command.confirmation == 0 } }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 1, startedByVehicle = true)
            sent.size shouldBeEqualTo 1
            currentTime shouldBeEqualTo ANSWER_DELAY + IN_PROGRESS_TIMEOUT
        }

    @ParameterizedTest
    @ValueSource(
        strings = ["other command", "other system", "other component", "other address", "other target", "old session"],
    )
    fun `GIVEN only a stranger's ACK WHEN sending an RTL THEN it is never taken as the answer`(stranger: String) =
        testScope.runTest {
            vehicleAnswers { strangerAck(stranger) }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 3)
        }

    @Test
    fun `GIVEN an ACK without its target extensions WHEN sending an RTL THEN it is the answer`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED, targetSystem = 0, targetComponent = 0) }

        val result = repository.returnToLaunch(SYSTEM)

        result shouldBeEqualTo CommandResult.Accepted
    }

    @Test
    fun `GIVEN an arm answered after its timeout WHEN arming THEN no answer and the late ACK is only logged`() =
        testScope.runTest {
            vehicleAnswers { null }
            answerLater(ack(MAV_RESULT_ACCEPTED, MAV_CMD_COMPONENT_ARM_DISARM), after = PAST_ONE_ATTEMPT)

            val result = repository.arm(SYSTEM)
            delay(LONG_AFTER)

            result shouldBeEqualTo noAnswer(attempts = 1)
            verifyOnce {
                Log.w(EXCHANGE_TAG, "ARM to system 1: late answer 0 dropped")
            }
        }

    @Test
    fun `GIVEN an RTL just ended without answer WHEN sending an RTL again THEN it is not sent`() = testScope.runTest {
        vehicleAnswers { null }
        repository.returnToLaunch(SYSTEM)

        val result = repository.returnToLaunch(SYSTEM)

        result shouldBeEqualTo CommandResult.NotSent(NotSentReason.BUSY)
        sent.size shouldBeEqualTo 3
    }

    @Test
    fun `GIVEN an RTL just ended without answer WHEN landing THEN the land is sent at once`() = testScope.runTest {
        vehicleAnswers { command -> acceptsOnlyLand(command) }
        repository.returnToLaunch(SYSTEM)

        val result = repository.land(SYSTEM)

        result shouldBeEqualTo CommandResult.Accepted
        currentTime shouldBeEqualTo THREE_ATTEMPTS + ANSWER_DELAY
    }

    @Test
    fun `GIVEN an RTL ended without answer past its late window WHEN sending an RTL again THEN it is sent`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED).takeIf { sent.size > 3 && command.confirmation == 0 } }
            repository.returnToLaunch(SYSTEM)
            delay(PAST_LATE_WINDOW)

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo CommandResult.Accepted
        }

    @Test
    fun `GIVEN an arm without answer 5 s ago WHEN arming again THEN it is not sent`() = testScope.runTest {
        vehicleAnswers { null }
        repository.arm(SYSTEM)
        delay(WITHIN_ARM_LATE_WINDOW)

        val result = repository.arm(SYSTEM)

        result shouldBeEqualTo CommandResult.NotSent(NotSentReason.BUSY)
        sent.size shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN an arm without answer past its 10 s late window WHEN arming again THEN it is sent`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED, command.command).takeIf { sent.size == 2 } }
            repository.arm(SYSTEM)
            delay(PAST_ARM_LATE_WINDOW)

            val result = repository.arm(SYSTEM)

            result shouldBeEqualTo CommandResult.Accepted
        }

    @Test
    fun `GIVEN an RTL being re-sent WHEN landing THEN the land is sent at once and the RTL is superseded`() =
        testScope.runTest {
            vehicleAnswers { command -> acceptsOnlyLand(command) }
            val rtl = async(start = CoroutineStart.UNDISPATCHED) { repository.returnToLaunch(SYSTEM) }
            delay(DURING_SECOND_ATTEMPT)

            val land = async(start = CoroutineStart.UNDISPATCHED) { repository.land(SYSTEM) }
            val result = listOf(rtl.await(), land.await())
            delay(LONG_AFTER)

            result shouldBeEqualTo listOf(CommandResult.Superseded, CommandResult.Accepted)
            sent.map { it.command to it.confirmation } shouldBeEqualTo listOf(
                MAV_CMD_NAV_RETURN_TO_LAUNCH to 0,
                MAV_CMD_NAV_RETURN_TO_LAUNCH to 1,
                MAV_CMD_NAV_LAND to 0,
            )
        }

    @Test
    fun `GIVEN an RTL superseded by a land WHEN the RTL's late ACK arrives THEN it is not the land's answer`() =
        testScope.runTest {
            vehicleAnswers { null }
            answerLater(ack(MAV_RESULT_ACCEPTED), after = DURING_SECOND_ATTEMPT + ANSWER_DELAY)
            backgroundScope.launch { repository.returnToLaunch(SYSTEM) }
            delay(DURING_SECOND_ATTEMPT)

            val result = repository.land(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 3)
        }

    @Test
    fun `GIVEN an RTL pending WHEN arming THEN the arm is not sent and the RTL goes on`() = testScope.runTest {
        vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED).takeIf { command.confirmation == 2 } }
        val rtl = async(start = CoroutineStart.UNDISPATCHED) { repository.returnToLaunch(SYSTEM) }
        delay(DURING_SECOND_ATTEMPT)

        val result = listOf(repository.arm(SYSTEM), rtl.await())

        result shouldBeEqualTo listOf(CommandResult.NotSent(NotSentReason.BUSY), CommandResult.Accepted)
        sent.map { it.command }.distinct() shouldBeEqualTo listOf(MAV_CMD_NAV_RETURN_TO_LAUNCH)
    }

    @Test
    fun `GIVEN an arm pending WHEN sending an RTL THEN the RTL is not sent and the arm goes on`() = testScope.runTest {
        vehicleAnswers { null }
        answerLater(ack(MAV_RESULT_ACCEPTED, MAV_CMD_COMPONENT_ARM_DISARM), after = HALF_AN_ATTEMPT)
        val arm = async(start = CoroutineStart.UNDISPATCHED) { repository.arm(SYSTEM) }

        val result = listOf(repository.returnToLaunch(SYSTEM), arm.await())

        result shouldBeEqualTo listOf(CommandResult.NotSent(NotSentReason.BUSY), CommandResult.Accepted)
        sent.map { it.command } shouldBeEqualTo listOf(MAV_CMD_COMPONENT_ARM_DISARM)
    }

    @Test
    fun `GIVEN an RTL being re-sent WHEN sending another RTL THEN the first one's late ACK answers it`() =
        testScope.runTest {
            vehicleAnswers { null }
            answerLater(ack(MAV_RESULT_ACCEPTED), after = DURING_SECOND_ATTEMPT + ANSWER_DELAY)
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.returnToLaunch(SYSTEM) }
            delay(DURING_SECOND_ATTEMPT)

            val result = listOf(first, async(start = CoroutineStart.UNDISPATCHED) { repository.returnToLaunch(SYSTEM) })
                .map { it.await() }

            result shouldBeEqualTo listOf(CommandResult.Superseded, CommandResult.Accepted)
        }

    @Test
    fun `GIVEN an RTL started and silent WHEN its ACK arrives in the late window THEN it is only logged`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_IN_PROGRESS).takeIf { command.confirmation == 0 } }
            answerLater(ack(MAV_RESULT_ACCEPTED), after = ANSWER_DELAY + IN_PROGRESS_TIMEOUT + HALF_AN_ATTEMPT)

            val result = repository.returnToLaunch(SYSTEM)
            delay(LONG_AFTER)

            result shouldBeEqualTo noAnswer(attempts = 1, startedByVehicle = true)
            verifyOnce {
                Log.w(EXCHANGE_TAG, "RETURN_TO_LAUNCH to system 1: late answer 0 dropped")
            }
        }

    @Tag("REQ-043")
    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay starts while the vehicle works on an RTL WHEN waiting THEN the wait ends at once`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_IN_PROGRESS).takeIf { command.confirmation == 0 } }
            backgroundScope.launch {
                delay(PAST_TWO_ATTEMPTS)
                sessions.value = REPLAY_SESSION
            }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo
                noAnswer(attempts = 1, startedByVehicle = true, retriesStopped = NotSentReason.REPLAY)
            currentTime shouldBeEqualTo PAST_TWO_ATTEMPTS
        }

    @Tag("REQ-043")
    @ParameterizedTest
    @EnumSource(NotSentReason::class, names = ["REPLAY", "NOT_ACTIVE_VEHICLE", "LINK_LOST", "ROUTE_CHANGED"])
    fun `GIVEN a closed route WHEN sending an RTL THEN nothing is sent, for the route's reason`(reason: NotSentReason) =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
            route = CommandRoute.Closed(reason)

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo CommandResult.NotSent(reason)
            sent shouldBeEqualTo emptyList()
        }

    @Tag("REQ-043")
    @Test
    fun `GIVEN the link is lost during the second attempt WHEN sending an RTL THEN no answer after two attempts`() =
        testScope.runTest {
            vehicleAnswers { null }
            backgroundScope.launch {
                delay(DURING_SECOND_ATTEMPT)
                route = CommandRoute.Closed(NotSentReason.LINK_LOST)
            }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 2, retriesStopped = NotSentReason.LINK_LOST)
            sent.size shouldBeEqualTo 2
        }

    @Tag("REQ-043")
    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay starts during the first attempt WHEN sending an RTL THEN the wait ends at once`() =
        testScope.runTest {
            vehicleAnswers { null }
            backgroundScope.launch {
                delay(HALF_AN_ATTEMPT)
                sessions.value = REPLAY_SESSION
            }

            val result = repository.returnToLaunch(SYSTEM)

            result shouldBeEqualTo noAnswer(attempts = 1, retriesStopped = NotSentReason.REPLAY)
            currentTime shouldBeEqualTo HALF_AN_ATTEMPT
        }

    @Test
    fun `GIVEN the socket fails to send WHEN sending an RTL THEN it is not sent`() = testScope.runTest {
        coEvery { sender.send(any<CommandLong>(), any<() -> CommandRoute>()) } returns
            CommandSendResult.NotSent(NotSentReason.SEND_FAILED)

        val result = repository.returnToLaunch(SYSTEM)

        result shouldBeEqualTo CommandResult.NotSent(NotSentReason.SEND_FAILED)
    }

    @Test
    fun `GIVEN its caller is cancelled WHEN an RTL is being re-sent THEN the RTL goes on until answered`() =
        testScope.runTest {
            vehicleAnswers { command -> ack(MAV_RESULT_ACCEPTED).takeIf { command.confirmation == 2 } }
            val caller = launch { repository.returnToLaunch(SYSTEM) }
            delay(HALF_AN_ATTEMPT)

            caller.cancel()
            delay(LONG_AFTER)

            sent.map { it.confirmation } shouldBeEqualTo listOf(0, 1, 2)
            verifyOnce {
                Log.i(REPOSITORY_TAG, "RETURN_TO_LAUNCH to system 1: Accepted")
            }
        }

    @Test
    fun `GIVEN an RTL without answer WHEN it ends THEN its result is logged once, without the vehicle's address`() =
        testScope.runTest {
            vehicleAnswers { null }

            repository.returnToLaunch(SYSTEM)

            verifyOnce {
                Log.w(
                    REPOSITORY_TAG,
                    "RETURN_TO_LAUNCH to system 1: NoAnswer(attempts=3, startedByVehicle=false, retriesStopped=null)",
                )
            }
            verifyNever {
                Log.w(any(), match<String> { VEHICLE_ADDRESS.port.toString() in it })
                Log.i(any(), any<String>())
            }
        }

    @Test
    fun `GIVEN the vehicle never answers WHEN sending an RTL THEN pending shows each attempt and then nothing`() =
        testScope.runTest {
            val states = mutableListOf<PendingCommand?>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.pending.toList(states) }
            vehicleAnswers { null }

            repository.returnToLaunch(SYSTEM)

            states shouldBeEqualTo listOf(
                null,
                pendingRtl(PendingState.Waiting(attempt = 1)),
                pendingRtl(PendingState.Waiting(attempt = 2)),
                pendingRtl(PendingState.Waiting(attempt = 3)),
                null,
            )
        }

    /**
     * The vehicle answers each COMMAND_LONG the sender lets out with [answer], [ANSWER_DELAY] later, or not at all when
     * it gives `null`. The sender reads the route as the real one does, under its lock, before each frame.
     */
    private fun TestScope.vehicleAnswers(answer: (CommandLong) -> ReceivedMessage?) {
        coEvery { sender.send(any<CommandLong>(), any<() -> CommandRoute>()) } coAnswers {
            when (val current = secondArg<() -> CommandRoute>()()) {
                is CommandRoute.Closed -> CommandSendResult.NotSent(current.reason)

                is CommandRoute.Open -> {
                    val command = firstArg<CommandLong>()
                    sent += command
                    answer(command)?.let { answerLater(it, after = ANSWER_DELAY) }
                    CommandSendResult.Sent
                }
            }
        }
    }

    /** Puts [answer] on the link [after] virtual ms from now. */
    private fun TestScope.answerLater(answer: ReceivedMessage, after: Long) {
        backgroundScope.launch {
            delay(after)
            messages.emit(answer)
        }
    }

    private fun strangerAck(stranger: String): ReceivedMessage {
        val ack = ack(MAV_RESULT_ACCEPTED)
        return when (stranger) {
            "other command" -> ack(MAV_RESULT_ACCEPTED, MAV_CMD_NAV_LAND)
            "other system" -> ack.copy(systemId = 2)
            "other component" -> ack.copy(componentId = 2)
            "other address" -> ack.copy(sender = OTHER_ADDRESS)
            "other target" -> ack(MAV_RESULT_ACCEPTED, targetSystem = 254, targetComponent = 190)
            "old session" -> ack.copy(session = SessionId(FIRST_SESSION.origin, FIRST_SESSION.epoch - 1))
            else -> error("Unknown stranger $stranger")
        }
    }

    private companion object {
        const val REPOSITORY_TAG = "CommandRepository"
        const val EXCHANGE_TAG = "CommandExchange"
        const val VEHICLE_SYSTEM_ID = 1
        const val AUTOPILOT_COMPONENT_ID = 1
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val MAV_CMD_NAV_RETURN_TO_LAUNCH = 20
        const val MAV_CMD_NAV_LAND = 21
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_DENIED = 2
        const val MAV_RESULT_IN_PROGRESS = 5
        const val UNKNOWN_PROGRESS = 255

        // Virtual milliseconds: an attempt waits 1.5 s, IN_PROGRESS 10 s more, the late window 1.5 s (arm's 10 s).
        const val ANSWER_DELAY = 50L
        const val HALF_AN_ATTEMPT = 750L
        const val PAST_ONE_ATTEMPT = 2_000L
        const val DURING_SECOND_ATTEMPT = 2_000L
        const val PAST_TWO_ATTEMPTS = 3_500L
        const val THREE_ATTEMPTS = 4_500L
        const val IN_PROGRESS_TIMEOUT = 10_000L
        const val PAST_LATE_WINDOW = 2_000L
        const val WITHIN_ARM_LATE_WINDOW = 5_000L
        const val PAST_ARM_LATE_WINDOW = 10_500L
        const val LONG_AFTER = 20_000L

        val SYSTEM = SystemId(VEHICLE_SYSTEM_ID)
        val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val OTHER_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_570)
        val OPEN_ROUTE = CommandRoute.Open(VEHICLE_ADDRESS)

        fun ack(
            result: Int,
            command: Int = MAV_CMD_NAV_RETURN_TO_LAUNCH,
            progress: Int = UNKNOWN_PROGRESS,
            targetSystem: Int = GCS_SYSTEM_ID,
            targetComponent: Int = GCS_COMPONENT_ID,
        ) = ReceivedMessage(
            systemId = VEHICLE_SYSTEM_ID,
            componentId = AUTOPILOT_COMPONENT_ID,
            message = CommandAck(command, result, progress, resultParam2 = 0, targetSystem, targetComponent),
            sender = VEHICLE_ADDRESS,
            session = FIRST_SESSION,
        )

        fun commandLong(command: Int, param1: Float = 0f) = CommandLong(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = AUTOPILOT_COMPONENT_ID,
            command = command,
            confirmation = 0,
            param1 = param1,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            param5 = 0f,
            param6 = 0f,
            param7 = 0f,
        )

        fun acceptsOnlyLand(command: CommandLong) =
            ack(MAV_RESULT_ACCEPTED, MAV_CMD_NAV_LAND).takeIf { command.command == MAV_CMD_NAV_LAND }

        fun noAnswer(attempts: Int, startedByVehicle: Boolean = false, retriesStopped: NotSentReason? = null) =
            CommandResult.NoAnswer(attempts, startedByVehicle, retriesStopped)

        fun pendingRtl(state: PendingState) = PendingCommand(SYSTEM, VehicleCommand.RETURN_TO_LAUNCH, state)
    }
}
