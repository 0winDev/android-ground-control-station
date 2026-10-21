package com.owindev.gcs.data.vehicle.telemetry

import android.util.Log
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
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
import kotlinx.coroutines.isActive
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
 *
 * The streams themselves are recorded straight into a real [TelemetryStore] on the scheduler's clock, as the
 * vehicle repository's listener does. A round of six accepted requests takes 300 ms (one 50 ms answer each),
 * so a stream is first allowed again 5.3 s in; the tests check well away from that edge. The vehicle never
 * streams HOME_POSITION here, as before home is set.
 */
@Tag("REQ-023")
class TelemetryRequesterTest {

    private val sender: GcsMessageSender = mockk()
    private val session: LinkSession = mockk()
    private val messages = MutableSharedFlow<ReceivedMessage>()
    private val sent = mutableListOf<CommandLong>()
    private val testScope = TestScope()
    private val store = TelemetryStore(TrailRule(StalenessRule()))

    private lateinit var requester: TelemetryRequester

    @BeforeEach
    fun setUp() {
        every { session.messages } returns messages
        requester = TelemetryRequester(
            sender = sender,
            session = session,
            store = store,
            rule = StalenessRule(),
            timeSource = testScope.testScheduler.timeSource,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN the vehicle accepts every request WHEN requesting THEN each stream is asked for once, in order`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }

            val result = requester.requestAll(TARGET)

            result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
            sent shouldBeEqualTo listOf(
                setMessageInterval(messageId = 30, intervalMicros = 100_000f),
                setMessageInterval(messageId = 33, intervalMicros = 200_000f),
                setMessageInterval(messageId = 74, intervalMicros = 250_000f),
                setMessageInterval(messageId = 24, intervalMicros = 500_000f),
                setMessageInterval(messageId = 1, intervalMicros = 1_000_000f),
                setMessageInterval(messageId = 242, intervalMicros = 1_000_000f),
            )
        }

    @Test
    fun `GIVEN the vehicle denies one request WHEN requesting THEN only that stream is rejected`() = testScope.runTest {
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
            StreamResult.Accepted,
        )
    }

    @Test
    fun `GIVEN the vehicle never answers WHEN requesting THEN every stream has no ACK after its timeout`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { null }
            val start = testScheduler.timeSource.markNow()

            val result = requester.requestAll(TARGET)

            result shouldBeEqualTo List(STREAMS) { StreamResult.NoAck }
            start.elapsedNow().inWholeMilliseconds shouldBeEqualTo STREAMS * ACK_TIMEOUT_MILLIS
        }

    @Test
    fun `GIVEN an IN_PROGRESS ACK before the final one WHEN requesting THEN the final result is kept`() =
        testScope.runTest {
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
    ) = testScope.runTest {
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
                session = FIRST_SESSION,
            )
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.NoAck }
    }

    @Test
    fun `GIVEN ACKs from another address WHEN requesting THEN they are ignored`() = testScope.runTest {
        mockLog()
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED).copy(sender = OTHER_ADDRESS) }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo List(STREAMS) { StreamResult.NoAck }
    }

    /**
     * A known limit (see the class KDoc): the ACK names no message, so ATTITUDE's late DENIED (1.02 s) is taken
     * as the answer to GLOBAL_POSITION_INT (sent at 1 s), and every later answer shifts by one.
     */
    @Test
    fun `GIVEN an ACK later than its timeout WHEN requesting THEN it answers the next request`() = testScope.runTest {
        mockLog()
        vehicleAnswers(after = { command ->
            if (command.param1 ==
                ATTITUDE_ID
            ) {
                LATE
            } else {
                ANSWER_DELAY
            }
        }) { command ->
            ack(if (command.param1 == ATTITUDE_ID) MAV_RESULT_DENIED else MAV_RESULT_ACCEPTED)
        }

        val result = requester.requestAll(TARGET)

        result shouldBeEqualTo listOf(
            StreamResult.NoAck,
            StreamResult.Rejected(MAV_RESULT_DENIED),
            StreamResult.Accepted,
            StreamResult.Accepted,
            StreamResult.Accepted,
            StreamResult.Accepted,
        )
    }

    @Test
    fun `GIVEN ACKs without extensions WHEN requesting THEN their zero targets are accepted as the answer`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED, targetSystem = 0, targetComponent = 0) }

            val result = requester.requestAll(TARGET)

            result shouldBeEqualTo List(STREAMS) { StreamResult.Accepted }
        }

    @Test
    fun `GIVEN the link is closed and then a send fails WHEN requesting THEN those streams are not sent`() =
        testScope.runTest {
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
                StreamResult.Accepted,
            )
        }

    @Test
    fun `GIVEN rounds that fail the same way and then recover WHEN requesting THEN each change is logged once`() =
        testScope.runTest {
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
                Log.w(TAG, "Telemetry stream 242 not set: NoAck")
                Log.i(TAG, "Telemetry stream 30 accepted again")
                Log.i(TAG, "Telemetry stream 33 accepted again")
                Log.i(TAG, "Telemetry stream 74 accepted again")
                Log.i(TAG, "Telemetry stream 24 accepted again")
                Log.i(TAG, "Telemetry stream 1 accepted again")
                Log.i(TAG, "Telemetry stream 242 accepted again")
            }
        }

    @Test
    fun `GIVEN every stream accepted from the start WHEN requesting twice THEN nothing is logged`() =
        testScope.runTest {
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
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
            val targets = flow {
                emit(TARGET)
                delay(BEFORE_REQUESTED_AGAIN)
                emit(null)
                delay(ONE_MINUTE)
                emit(TARGET)
                delay(BEFORE_REQUESTED_AGAIN)
                emit(null)
            }

            requester.run(targets, isHeard = { true })

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + STREAM_IDS
        }

    @Test
    fun `GIVEN the vehicle is lost during a round WHEN running THEN the rest of the round is not requested`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { null }
            val targets = flow {
                emit(TARGET)
                delay(ONE_AND_A_HALF_TIMEOUTS)
                emit(null)
            }

            requester.run(targets, isHeard = { true })

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS.take(2)
        }

    @Test
    fun `GIVEN every stream keeps arriving WHEN running THEN none is requested again`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
        vehicleStreams(SYSTEM_1)

        requester.run(isHeard = { true }, targets = connectedFor(TWENTY_SECONDS))

        sent.map { it.param1 } shouldBeEqualTo STREAM_IDS
    }

    @Test
    fun `GIVEN one stream stops arriving WHEN running THEN only that stream is requested again`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
        vehicleStreams(SYSTEM_1, stopAt = mapOf(ATTITUDE to TWO_SECONDS))

        requester.run(isHeard = { true }, targets = connectedFor(SIX_SECONDS))

        sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + ATTITUDE_ID
    }

    @Test
    fun `GIVEN a stream that stops and resumes before it may be requested again WHEN running THEN is not requested`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
            vehicleStreams(
                SYSTEM_1,
                stopAt = mapOf(ATTITUDE to TWO_SECONDS),
                resumeAt = mapOf(ATTITUDE to FOUR_SECONDS),
            )

            requester.run(isHeard = { true }, targets = connectedFor(SIX_SECONDS))

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS
        }

    @Test
    fun `GIVEN a stream that never arrives WHEN running THEN is requested again every 5 seconds`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
        vehicleStreams(SYSTEM_1, stopAt = mapOf(SYS_STATUS to 0L))

        requester.run(isHeard = { true }, targets = connectedFor(SIXTEEN_SECONDS))

        sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + listOf(SYS_STATUS_ID, SYS_STATUS_ID, SYS_STATUS_ID)
    }

    @Test
    fun `GIVEN a silent stream rejected by the vehicle WHEN running THEN is still requested again`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { command ->
                ack(if (command.param1 == SYS_STATUS_ID) MAV_RESULT_DENIED else MAV_RESULT_ACCEPTED)
            }
            vehicleStreams(SYSTEM_1, stopAt = mapOf(SYS_STATUS to 0L))

            requester.run(isHeard = { true }, targets = connectedFor(SIX_SECONDS))

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + SYS_STATUS_ID
        }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a vehicle without home and silent streams WHEN running THEN HOME_POSITION is never requested again`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }

            requester.run(isHeard = { true }, targets = connectedFor(SIXTEEN_SECONDS))

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + List(REQUESTED_AGAIN_IN_SIXTEEN_SECONDS) { WATCHED_IDS }
                .flatten()
        }

    @Tag("REQ-016")
    @Test
    fun `GIVEN only another vehicle streams WHEN running THEN the target's streams are requested again`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
            vehicleStreams(SYSTEM_2)

            requester.run(isHeard = { true }, targets = connectedFor(SIX_SECONDS))

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS + WATCHED_IDS
        }

    @Test
    fun `GIVEN a silent stream while the vehicle is not heard right now WHEN running THEN is not requested again`() =
        testScope.runTest {
            vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }

            requester.run(isHeard = { false }, targets = connectedFor(SIXTEEN_SECONDS))

            sent.map { it.param1 } shouldBeEqualTo STREAM_IDS
        }

    @Test
    fun `GIVEN the vehicle is lost WHEN its streams go silent THEN none is requested again`() = testScope.runTest {
        vehicleAnswers { ack(MAV_RESULT_ACCEPTED) }
        val targets = flow {
            emit(TARGET)
            delay(BEFORE_REQUESTED_AGAIN)
            emit(null)
            delay(ONE_MINUTE)
        }

        requester.run(targets, isHeard = { true })

        sent.map { it.param1 } shouldBeEqualTo STREAM_IDS
    }

    /** The target, CONNECTED from the start and lost after [millis]. */
    private fun connectedFor(millis: Long) = flow {
        emit(TARGET)
        delay(millis)
        emit(null)
    }

    /**
     * The vehicle with [systemId] streams every kind every [STREAM_PERIOD] ms into the store, each one
     * stopping at its [stopAt] and coming back at its [resumeAt] (virtual ms), as long as the test runs.
     */
    private fun TestScope.vehicleStreams(
        systemId: Int,
        stopAt: Map<MavlinkMessage, Long> = emptyMap(),
        resumeAt: Map<MavlinkMessage, Long> = emptyMap(),
    ) {
        val start = testScheduler.timeSource.markNow()
        backgroundScope.launch {
            while (isActive) {
                val now = start.elapsedNow().inWholeMilliseconds
                STREAMED.forEach { message ->
                    val stopped = now >= (stopAt[message] ?: Long.MAX_VALUE) &&
                        now < (resumeAt[message] ?: Long.MAX_VALUE)
                    if (!stopped) {
                        store.record(
                            ReceivedMessage(
                                systemId,
                                AUTOPILOT_COMPONENT_ID,
                                message,
                                VEHICLE_ADDRESS,
                                session = FIRST_SESSION,
                            ),
                            testScheduler.timeSource.markNow(),
                        )
                    }
                }
                delay(STREAM_PERIOD)
            }
        }
    }

    /**
     * The vehicle answers each COMMAND_LONG sent with [answer], [after] virtual ms later, or not at all when
     * [answer] gives `null`.
     */
    private fun TestScope.vehicleAnswers(
        after: (CommandLong) -> Long = { ANSWER_DELAY },
        answer: (CommandLong) -> ReceivedMessage?,
    ) {
        coEvery { sender.send(any<CommandLong>(), VEHICLE_ADDRESS) } coAnswers {
            val command = firstArg<CommandLong>()
            sent += command
            answer(command)?.let { answerLater(it, after = after(command)) }
            SendResult.Sent
        }
    }

    /** Puts [answers] on the link after the send returns, as a real vehicle's reply arrives later. */
    private fun TestScope.answerLater(vararg answers: ReceivedMessage, after: Long = ANSWER_DELAY) {
        backgroundScope.launch {
            delay(after)
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
        const val STREAMS = 6
        const val VFR_HUD_ID = 74f
        const val ATTITUDE_ID = 30f
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_DENIED = 2
        const val MAV_RESULT_IN_PROGRESS = 5
        const val ACK_TIMEOUT_MILLIS = 1_000L
        const val ANSWER_DELAY = 50L

        // 20 ms past the timeout: lands while the next request waits, before that request's own answer (50 ms).
        const val LATE = 1_020L
        const val ONE_MINUTE = 60_000L
        const val SYS_STATUS_ID = 1f
        const val SYSTEM_1 = 1
        const val SYSTEM_2 = 2
        const val STREAM_PERIOD = 100L
        const val TWO_SECONDS = 2_000L
        const val FOUR_SECONDS = 4_000L
        const val SIX_SECONDS = 6_000L
        const val SIXTEEN_SECONDS = 16_000L
        const val TWENTY_SECONDS = 20_000L

        // Well before the first request again, which is allowed 5.25 s in.
        const val BEFORE_REQUESTED_AGAIN = 3_000L

        // The first request times out at 1 s, so the second one is in flight when the vehicle is lost.
        const val ONE_AND_A_HALF_TIMEOUTS = 1_500L

        val STREAM_IDS = listOf(30f, 33f, 74f, 24f, 1f, 242f)
        val WATCHED_IDS = listOf(30f, 33f, 74f, 24f, 1f)

        // Allowed again at 5.3 s, 10.3 s and 15.3 s.
        const val REQUESTED_AGAIN_IN_SIXTEEN_SECONDS = 3
        val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val OTHER_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_570)
        val TARGET = TelemetryTarget(SystemId(VEHICLE_SYSTEM_ID), VEHICLE_ADDRESS)

        // One message of each streamed kind; their values do not matter here, only when they arrive.
        val ATTITUDE = MavlinkMessage.Attitude(
            timeBootMs = 0,
            roll = 0f,
            pitch = 0f,
            yaw = 0f,
            rollspeed = 0f,
            pitchspeed = 0f,
            yawspeed = 0f,
        )
        val GLOBAL_POSITION_INT = MavlinkMessage.GlobalPositionInt(
            timeBootMs = 0,
            lat = 0,
            lon = 0,
            alt = 0,
            relativeAlt = 0,
            vx = 0,
            vy = 0,
            vz = 0,
            hdg = 0,
        )
        val VFR_HUD = MavlinkMessage.VfrHud(
            airspeed = 0f,
            groundspeed = 0f,
            heading = 0,
            throttle = 0,
            alt = 0f,
            climb = 0f,
        )
        val GPS_RAW_INT = MavlinkMessage.GpsRawInt(
            timeUsec = 0,
            fixType = 0,
            lat = 0,
            lon = 0,
            alt = 0,
            eph = 0,
            epv = 0,
            vel = 0,
            cog = 0,
            satellitesVisible = 0,
            altEllipsoid = 0,
            hAcc = 0,
            vAcc = 0,
            velAcc = 0,
            hdgAcc = 0,
            yaw = 0,
        )
        val SYS_STATUS = MavlinkMessage.SysStatus(
            onboardControlSensorsPresent = 0,
            onboardControlSensorsEnabled = 0,
            onboardControlSensorsHealth = 0,
            load = 0,
            voltageBattery = 0,
            currentBattery = 0,
            batteryRemaining = 0,
            dropRateComm = 0,
            errorsComm = 0,
            errorsCount1 = 0,
            errorsCount2 = 0,
            errorsCount3 = 0,
            errorsCount4 = 0,
            onboardControlSensorsPresentExtended = 0,
            onboardControlSensorsEnabledExtended = 0,
            onboardControlSensorsHealthExtended = 0,
        )
        val STREAMED: List<MavlinkMessage> = listOf(ATTITUDE, GLOBAL_POSITION_INT, VFR_HUD, GPS_RAW_INT, SYS_STATUS)

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
                session = FIRST_SESSION,
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
