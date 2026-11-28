package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.domain.route.upload.BadRequest
import com.owindev.gcs.core.domain.route.upload.MissionResult
import com.owindev.gcs.core.domain.route.upload.OnBoardMission
import com.owindev.gcs.core.domain.route.upload.UploadNotSentReason
import com.owindev.gcs.core.domain.route.upload.UploadResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequest
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import com.owindev.gcs.data.vehicle.mission.MissionUploadMachine.State
import com.owindev.gcs.data.vehicle.mission.MissionUploadMachine.Step
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** One test per arrow of ADR 0010's diagram, on a plan of home, a take off and an RTL. */
@Tag("REQ-056")
class MissionUploadMachineTest {

    private val items = listOf(home(), item(seq = 1), item(seq = 2))
    private val plan = requireNotNull(MissionUploadPlan.of(items))
    private val count = plan.countMessage()

    private lateinit var machine: MissionUploadMachine

    @BeforeEach
    fun setUp() {
        machine = MissionUploadMachine(plan)
    }

    @Test
    fun `GIVEN a plan WHEN started THEN the first count is sent and answered within 1,5 s`() {
        val result = machine.start()

        result shouldBeEqualTo Step(State.SendingCount(attempt = 1), count, 1.5.seconds)
    }

    @Test
    fun `GIVEN the first count unanswered WHEN its wait passes THEN the second is sent with 1,5 s`() {
        val state = State.SendingCount(attempt = 1)

        val result = machine.onSilence(state)

        result shouldBeEqualTo Step(State.SendingCount(attempt = 2), count, 1.5.seconds)
    }

    @Test
    fun `GIVEN the second count unanswered WHEN its wait passes THEN the last is sent with 9 s`() {
        val state = State.SendingCount(attempt = 2)

        val result = machine.onSilence(state)

        result shouldBeEqualTo Step(State.SendingCount(attempt = 3), count, 9.seconds)
    }

    @Test
    fun `GIVEN the last count unanswered WHEN its wait passes THEN no answer with the previous mission maybe cut`() {
        val state = State.SendingCount(attempt = 3)

        val result = machine.onSilence(state)

        result shouldBeEqualTo ended(UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(count = 3)))
    }

    @Test
    fun `GIVEN a count sent WHEN MISSION_REQUEST asks for seq 0 THEN home's placeholder is sent with 9 s`() {
        val state = State.SendingCount(attempt = 2)

        val result = machine.on(state, request(seq = 0))

        result shouldBeEqualTo Step(State.Transferring(highest = 0, sent = true), items[0], 9.seconds)
    }

    @Test
    fun `GIVEN an item sent WHEN MISSION_REQUEST_INT asks for the next THEN that item is sent with 9 s`() {
        val state = State.Transferring(highest = 0, sent = true)

        val result = machine.on(state, requestInt(seq = 1))

        result shouldBeEqualTo Step(State.Transferring(highest = 1, sent = true), items[1], 9.seconds)
    }

    @Test
    fun `GIVEN item 1 sent WHEN seq 0 is asked for again THEN item 0 is sent again and 1 stays the highest`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, request(seq = 0))

        result shouldBeEqualTo Step(State.Transferring(highest = 1, sent = true), items[0], 9.seconds)
    }

    @Test
    fun `GIVEN item 1 sent WHEN seq 1 is asked for again THEN item 1 is sent again`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, request(seq = 1))

        result shouldBeEqualTo Step(State.Transferring(highest = 1, sent = true), items[1], 9.seconds)
    }

    @Test
    fun `GIVEN item 1 sent WHEN seq 3 of 3 is asked for THEN failed with a partial mission`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, request(seq = 3))

        result shouldBeEqualTo ended(UploadResult.Failed(BadRequest.OutOfRange(3), partial(1..2)))
    }

    @Test
    fun `GIVEN a count sent WHEN seq 3 of 3 is asked for THEN failed with the previous mission maybe cut`() {
        val state = State.SendingCount(attempt = 1)

        val result = machine.on(state, requestInt(seq = 3))

        result shouldBeEqualTo ended(UploadResult.Failed(BadRequest.OutOfRange(3), OnBoardMission.PreviousCut(3)))
    }

    @Test
    fun `GIVEN item 1 sent WHEN the fence's seq 2 is asked for THEN failed for the other mission type`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, request(seq = 2, missionType = MAV_MISSION_TYPE_FENCE))

        result shouldBeEqualTo ended(
            UploadResult.Failed(BadRequest.OtherMissionType(MAV_MISSION_TYPE_FENCE), partial(1..2)),
        )
    }

    @Test
    fun `GIVEN the last item sent WHEN ACCEPTED comes THEN the upload is accepted`() {
        val state = State.Transferring(highest = 2, sent = true)

        val result = machine.on(state, ack(type = MAV_MISSION_ACCEPTED))

        result shouldBeEqualTo ended(UploadResult.Accepted)
    }

    @Test
    fun `GIVEN the last item asked for but not sent WHEN ACCEPTED comes THEN it is not this upload's`() {
        val state = State.Transferring(highest = 2, sent = false)

        val result = machine.on(state, ack(type = MAV_MISSION_ACCEPTED))

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN an item before the last sent WHEN ACCEPTED comes THEN it is not this upload's`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, ack(type = MAV_MISSION_ACCEPTED))

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN an item sent twice WHEN INVALID_SEQUENCE comes THEN it is ignored and the wait restarts at 9 s`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, ack(type = MAV_MISSION_INVALID_SEQUENCE))

        result shouldBeEqualTo Step(state, wait = 9.seconds)
    }

    @Test
    fun `GIVEN item 1 sent WHEN an error ACK comes THEN rejected with the items before it stored`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.on(state, ack(type = MAV_MISSION_INVALID_PARAM1))

        result shouldBeEqualTo ended(UploadResult.Rejected(MissionResult.INVALID_PARAM1, partial(1..1)))
    }

    @Test
    fun `GIVEN a count sent WHEN NO_SPACE comes THEN rejected with the previous mission untouched`() {
        val state = State.SendingCount(attempt = 2)

        val result = machine.on(state, ack(type = MAV_MISSION_NO_SPACE))

        result shouldBeEqualTo ended(UploadResult.Rejected(MissionResult.NO_SPACE, OnBoardMission.Previous))
    }

    @Test
    fun `GIVEN the last count sent WHEN OPERATION_CANCELLED comes THEN rejected with the previous mission cut`() {
        val state = State.SendingCount(attempt = 3)

        val result = machine.on(state, ack(type = MAV_MISSION_OPERATION_CANCELLED))

        result shouldBeEqualTo ended(
            UploadResult.Rejected(MissionResult.OPERATION_CANCELLED, OnBoardMission.PreviousCut(count = 3)),
        )
    }

    @Test
    fun `GIVEN a count sent WHEN ACCEPTED or INVALID_SEQUENCE comes THEN neither is this upload's`() {
        val state = State.SendingCount(attempt = 1)

        val result = listOf(MAV_MISSION_ACCEPTED, MAV_MISSION_INVALID_SEQUENCE).map { machine.on(state, ack(it)) }

        result shouldBeEqualTo listOf(null, null)
    }

    @Test
    fun `GIVEN an upload WHEN an ACK of the fence comes THEN it is ignored in every phase`() {
        val states = listOf(State.SendingCount(attempt = 1), State.Transferring(highest = 1, sent = true))

        val result = states.map { machine.on(it, ack(MAV_MISSION_NO_SPACE, missionType = MAV_MISSION_TYPE_FENCE)) }

        result shouldBeEqualTo listOf(null, null)
    }

    @Test
    fun `GIVEN an upload WHEN telemetry comes THEN it is no event and keeps the running wait`() {
        val states = listOf(State.SendingCount(attempt = 1), State.Transferring(highest = 1, sent = true))

        val result = states.flatMap { state -> TELEMETRY.map { machine.on(state, it) } }

        result shouldBeEqualTo listOf(null, null, null, null)
    }

    @Test
    fun `GIVEN item 1 sent WHEN 9 s pass in silence THEN no answer with item 1 maybe stored`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.onSilence(state)

        result shouldBeEqualTo ended(UploadResult.NoAnswer(stoppedBy = null, partial(1..2)))
    }

    @Test
    fun `GIVEN the last item sent WHEN 9 s pass in silence THEN no answer with the whole route maybe stored`() {
        val state = State.Transferring(highest = 2, sent = true)

        val result = machine.onSilence(state)

        result shouldBeEqualTo ended(UploadResult.NoAnswer(stoppedBy = null, partial(2..3)))
    }

    @Test
    fun `GIVEN the first count WHEN its send is refused THEN not sent`() {
        val state = State.SendingCount(attempt = 1)

        val result = machine.onSendRefused(state, NotSentReason.LinkLost)

        result shouldBeEqualTo ended(UploadResult.NotSent(UploadNotSentReason.Refused(NotSentReason.LinkLost)))
    }

    @Test
    fun `GIVEN the second count WHEN its send is refused THEN the vehicle is outwaited 9 s`() {
        val state = State.SendingCount(attempt = 2)

        val result = machine.onSendRefused(state, NotSentReason.RouteChanged)

        result shouldBeEqualTo Step(
            State.Stopping(NotSentReason.RouteChanged, OnBoardMission.PreviousCut(count = 3)),
            wait = 9.seconds,
        )
    }

    @Test
    fun `GIVEN item 1 to send WHEN its send is refused THEN the vehicle is outwaited with item 1 not stored`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.onSendRefused(state, NotSentReason.KeyChanged)

        result shouldBeEqualTo Step(State.Stopping(NotSentReason.KeyChanged, partial(1..1)), wait = 9.seconds)
    }

    @Test
    fun `GIVEN a count sent WHEN the sender stops THEN the vehicle is outwaited 9 s`() {
        val state = State.SendingCount(attempt = 1)

        val result = machine.onStop(state, NotSentReason.Replay)

        result shouldBeEqualTo Step(
            State.Stopping(NotSentReason.Replay, OnBoardMission.PreviousCut(count = 3)),
            wait = 9.seconds,
        )
    }

    @Test
    fun `GIVEN item 1 sent WHEN the sender stops THEN the vehicle is outwaited with item 1 maybe stored`() {
        val state = State.Transferring(highest = 1, sent = true)

        val result = machine.onStop(state, NotSentReason.LinkLost)

        result shouldBeEqualTo Step(State.Stopping(NotSentReason.LinkLost, partial(1..2)), wait = 9.seconds)
    }

    @Test
    fun `GIVEN a stopped upload WHEN the vehicle asks or answers THEN nothing is sent nor ended`() {
        val state = State.Stopping(NotSentReason.LinkLost, partial(1..2))

        val result = listOf(request(seq = 2), ack(MAV_MISSION_ACCEPTED)).map { machine.on(state, it) }

        result shouldBeEqualTo listOf(null, null)
    }

    @Test
    fun `GIVEN a stopped upload WHEN 9 s pass THEN no answer with why it stopped and what the vehicle may hold`() {
        val state = State.Stopping(NotSentReason.LinkLost, partial(1..2))

        val result = machine.onSilence(state)

        result shouldBeEqualTo ended(UploadResult.NoAnswer(NotSentReason.LinkLost, partial(1..2)))
    }

    @Test
    fun `GIVEN an ended upload WHEN anything happens THEN it stays ended`() {
        val state = State.Ended(UploadResult.Accepted)

        val result = listOf(
            machine.on(state, request(seq = 0)),
            machine.on(state, ack(MAV_MISSION_NO_SPACE)),
            machine.onSilence(state),
            machine.onSendRefused(state, NotSentReason.LinkLost),
            machine.onStop(state, NotSentReason.LinkLost),
        )

        result shouldBeEqualTo listOf(null, null, Step(state), Step(state), Step(state))
    }

    @ParameterizedTest
    @CsvSource(
        "1, ERROR",
        "2, UNSUPPORTED_FRAME",
        "3, UNSUPPORTED",
        "4, NO_SPACE",
        "5, INVALID",
        "6, INVALID_PARAM1",
        "7, INVALID_PARAM2",
        "8, INVALID_PARAM3",
        "9, INVALID_PARAM4",
        "10, INVALID_PARAM5_X",
        "11, INVALID_PARAM6_Y",
        "12, INVALID_PARAM7",
        "14, DENIED",
        "15, OPERATION_CANCELLED",
        "16, UNKNOWN",
        "255, UNKNOWN",
    )
    fun `GIVEN a MAV_MISSION_RESULT WHEN read THEN its name from common_xml`(type: Int, expected: MissionResult) {
        val result = missionResultOf(type)

        result shouldBeEqualTo expected
    }

    private fun partial(routeItems: IntRange) = OnBoardMission.Partial(routeItems, count = 3)

    private fun ended(result: UploadResult) = Step(State.Ended(result))

    private companion object {
        const val MAV_MISSION_TYPE_MISSION = 0
        const val MAV_MISSION_TYPE_FENCE = 1
        const val MAV_MISSION_ACCEPTED = 0
        const val MAV_MISSION_NO_SPACE = 4
        const val MAV_MISSION_INVALID_PARAM1 = 6
        const val MAV_MISSION_INVALID_SEQUENCE = 13
        const val MAV_MISSION_OPERATION_CANCELLED = 15
        const val GCS_SYSTEM = 255
        const val GCS_COMPONENT = 190

        val TELEMETRY: List<MavlinkMessage> =
            listOf(MavlinkMessage.MissionItemReached(seq = 1), MavlinkMessage.ExtendedSysState(0, 1))

        fun request(seq: Int, missionType: Int = MAV_MISSION_TYPE_MISSION) =
            MissionRequest(GCS_SYSTEM, GCS_COMPONENT, seq, missionType)

        fun requestInt(seq: Int) = MissionRequestInt(GCS_SYSTEM, GCS_COMPONENT, seq, MAV_MISSION_TYPE_MISSION)

        fun ack(type: Int, missionType: Int = MAV_MISSION_TYPE_MISSION) =
            MissionAck(GCS_SYSTEM, GCS_COMPONENT, type, missionType, opaqueId = 0)

        fun home() = item(seq = 0).copy(frame = 0, command = 16, current = 1)

        fun item(seq: Int) = MissionItemInt(
            targetSystem = 1,
            targetComponent = 1,
            seq = seq,
            frame = 3,
            command = 16,
            current = 0,
            autocontinue = 1,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            x = -353_633_515,
            y = 1_491_652_412,
            z = 10f,
            missionType = 0,
        )
    }
}
