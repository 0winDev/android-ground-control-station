package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.domain.route.upload.BadRequest
import com.owindev.gcs.core.domain.route.upload.MissionResult
import com.owindev.gcs.core.domain.route.upload.OnBoardMission
import com.owindev.gcs.core.domain.route.upload.UploadNotSentReason
import com.owindev.gcs.core.domain.route.upload.UploadResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequest
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The upload of ADR 0010, one state and one transition per box and arrow of its diagram. It only decides: the caller
 * sends [Step.send], waits [Step.wait] and hands back what the vehicle sent, from this upload's route only.
 */
internal class MissionUploadMachine(private val plan: MissionUploadPlan) {

    sealed interface State {

        /** The [attempt]th MISSION_COUNT was asked for. */
        data class SendingCount(val attempt: Int) : State

        /**
         * [highest] is the highest seq the vehicle asked for, [sent] whether its item was asked to be sent, and [asked]
         * the seq of the item this step sends.
         */
        data class Transferring(val highest: Int, val sent: Boolean, val asked: Int = highest) : State

        /**
         * The upload ended with [result] while the vehicle may still be receiving: nothing more is sent or taken, and
         * [result] comes once the vehicle has given up.
         */
        data class Outwaiting(val result: UploadResult) : State

        data class Ended(val result: UploadResult) : State
    }

    /**
     * What to do on entering [state]: send [send] if any, then wait [wait] for the vehicle, a new wait replacing the
     * running one; `null` keeps the running one, or none once [State.Ended].
     */
    data class Step(val state: State, val send: MavlinkMessage? = null, val wait: Duration? = null)

    fun start(): Step = Step(State.SendingCount(attempt = 1), plan.countMessage(), COUNT_TIMEOUT)

    /** `null` for a message that is no part of an upload, such as telemetry: it never restarts a wait. */
    fun on(state: State, message: MavlinkMessage): Step? = when (message) {
        is MissionRequest -> onRequest(state, message.seq, message.missionType)

        is MissionRequestInt -> onRequest(state, message.seq, message.missionType)

        is MissionAck -> when {
            message.missionType != MAV_MISSION_TYPE_MISSION -> null
            state is State.SendingCount -> onCountAck(state, message.type)
            state is State.Transferring -> onTransferAck(state, message.type)
            else -> null
        }

        else -> null
    }

    /** The step's wait passed with nothing this upload takes. */
    fun onSilence(state: State): Step = when (state) {
        is State.SendingCount -> if (state.attempt < COUNT_ATTEMPTS) {
            val attempt = state.attempt + 1
            Step(State.SendingCount(attempt), plan.countMessage(), countWait(attempt))
        } else {
            ended(UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(plan.count)))
        }

        is State.Transferring -> ended(UploadResult.NoAnswer(stoppedBy = null, partial(state, plan.count)))

        is State.Outwaiting -> ended(state.result)

        is State.Ended -> Step(state)
    }

    /** The sender refused the message [state]'s step asked for: it never left. */
    fun onSendRefused(state: State, reason: NotSentReason): Step = when (state) {
        is State.SendingCount -> if (state.attempt == 1) {
            ended(UploadResult.NotSent(UploadNotSentReason.Refused(reason)))
        } else {
            outwait(UploadResult.NoAnswer(reason, OnBoardMission.PreviousCut(plan.count)))
        }

        // A refused repeat of an earlier item leaves the highest one as sent as it was.
        is State.Transferring -> {
            val sent = state.sent && state.asked != state.highest
            outwait(UploadResult.NoAnswer(reason, partial(state.copy(sent = sent), plan.count)))
        }

        is State.Outwaiting, is State.Ended -> Step(state)
    }

    /**
     * The whole upload outlasted [uploadLimit]: requests or `INVALID_SEQUENCE` that kept coming, unsigned until v0.6,
     * no longer keep it open.
     */
    fun onUploadLimit(state: State): Step = when (state) {
        is State.SendingCount -> outwait(
            UploadResult.NoAnswer(stoppedBy = null, OnBoardMission.PreviousCut(plan.count)),
        )

        is State.Transferring -> outwait(UploadResult.NoAnswer(stoppedBy = null, partial(state, plan.count)))

        is State.Outwaiting, is State.Ended -> Step(state)
    }

    /** The sender stopped the upload for [reason] between sends. */
    fun onStop(state: State, reason: NotSentReason): Step = when (state) {
        is State.SendingCount -> outwait(UploadResult.NoAnswer(reason, OnBoardMission.PreviousCut(plan.count)))
        is State.Transferring -> outwait(UploadResult.NoAnswer(reason, partial(state, plan.count)))
        is State.Outwaiting, is State.Ended -> Step(state)
    }

    private fun onRequest(state: State, seq: Int, missionType: Int): Step? {
        val stored = when (state) {
            is State.SendingCount -> OnBoardMission.PreviousCut(plan.count)
            is State.Transferring -> partial(state, plan.count)
            is State.Outwaiting, is State.Ended -> return null
        }
        return when {
            // The vehicle may still be receiving this upload: it is outwaited before the result.
            missionType != MAV_MISSION_TYPE_MISSION ->
                outwait(UploadResult.Failed(BadRequest.OtherMissionType(missionType), stored))

            seq !in plan.items.indices -> outwait(UploadResult.Failed(BadRequest.OutOfRange(seq), stored))

            else -> {
                val highest = if (state is State.Transferring) maxOf(state.highest, seq) else seq
                val sent = if (state is State.Transferring && seq < state.highest) state.sent else true
                Step(State.Transferring(highest, sent, asked = seq), plan.items[seq], SILENCE_TIMEOUT)
            }
        }
    }

    private fun onCountAck(state: State.SendingCount, type: Int): Step? = when (type) {
        // A count is never accepted before its items, and it draws no INVALID_SEQUENCE: not this upload's.
        MAV_MISSION_ACCEPTED, MAV_MISSION_INVALID_SEQUENCE -> null

        // The vehicle gave up on a count that reached it, so it cut its mission (`MissionItemProtocol::update`).
        MAV_MISSION_OPERATION_CANCELLED ->
            ended(UploadResult.Rejected(MissionResult.OPERATION_CANCELLED, OnBoardMission.PreviousCut(plan.count)))

        // Refused before `truncate` (`handle_mission_count`); a later count may have reached the vehicle after the one
        // refused, so its refusal can come while the vehicle receives.
        else -> if (state.attempt == 1) {
            ended(UploadResult.Rejected(missionResultOf(type), OnBoardMission.Previous))
        } else {
            outwait(UploadResult.Rejected(missionResultOf(type), OnBoardMission.PreviousCut(plan.count)))
        }
    }

    private fun onTransferAck(state: State.Transferring, type: Int): Step? = when (type) {
        MAV_MISSION_ACCEPTED ->
            if (state.highest == plan.count - 1 && state.sent) ended(UploadResult.Accepted) else null

        // ArduPilot answers an item it did not ask for with it and goes on (`MissionItemProtocol.cpp`).
        MAV_MISSION_INVALID_SEQUENCE -> Step(state, wait = SILENCE_TIMEOUT)

        // The vehicle's own timeout: the item sent before its silence may be stored.
        MAV_MISSION_OPERATION_CANCELLED ->
            ended(UploadResult.Rejected(MissionResult.OPERATION_CANCELLED, partial(state, plan.count)))

        // A refused item ends the upload unstored (`handle_mission_item`).
        else -> ended(
            UploadResult.Rejected(
                missionResultOf(type),
                OnBoardMission.Partial(state.highest..state.highest, plan.count),
            ),
        )
    }

    companion object {

        /** MISSION_COUNT is sent at most this many times (ADR 0010). */
        const val COUNT_ATTEMPTS = 3

        /** The wait after each count but the last, as for a command (ADR 0008). */
        val COUNT_TIMEOUT: Duration = 1.5.seconds

        /**
         * ArduPilot's 8 s (`upload_timeout_ms`, `MissionItemProtocol.h`) plus 1 s for the round trip: every result
         * comes once the vehicle has stopped receiving.
         */
        val SILENCE_TIMEOUT: Duration = 9.seconds

        /** The last count is outwaited like any silence, since a count that arrived starts the vehicle's 8 s. */
        fun countWait(attempt: Int): Duration = if (attempt < COUNT_ATTEMPTS) COUNT_TIMEOUT else SILENCE_TIMEOUT

        private const val MAV_MISSION_ACCEPTED = 0
        private const val MAV_MISSION_INVALID_SEQUENCE = 13
        private const val MAV_MISSION_OPERATION_CANCELLED = 15
    }
}

/** The longest the count can take: every attempt unanswered (1.5 s, 1.5 s, then 9 s). */
private val COUNT_PHASE: Duration =
    MissionUploadMachine.COUNT_TIMEOUT * (MissionUploadMachine.COUNT_ATTEMPTS - 1) +
        MissionUploadMachine.SILENCE_TIMEOUT

/**
 * Each item's share of the limit: three of the vehicle's own requests for it, which SITL sent 0.98 to 1.06 s apart
 * (#151), so two lost exchanges per item on average, or a link 3 s slow per round trip.
 */
private val PER_ITEM: Duration = 3.seconds

/**
 * The longest an upload of [count] items may take before the vehicle is outwaited and it ends as no answer (ADR
 * 0010): 12 s for the count, then [PER_ITEM] for each item, home included, so 495 s for the 160 items of REQ-055.
 */
internal fun uploadLimit(count: Int): Duration = COUNT_PHASE + PER_ITEM * count

/** Asking for seq [State.Transferring.highest] means every item before it is stored; the one sent may be too. */
private fun partial(state: MissionUploadMachine.State.Transferring, count: Int): OnBoardMission.Partial {
    val atMost = if (state.sent) state.highest + 1 else state.highest
    return OnBoardMission.Partial(state.highest..atMost, count)
}

private fun outwait(result: UploadResult) = MissionUploadMachine.Step(
    MissionUploadMachine.State.Outwaiting(result),
    wait = MissionUploadMachine.SILENCE_TIMEOUT,
)

private fun ended(result: UploadResult) = MissionUploadMachine.Step(MissionUploadMachine.State.Ended(result))

private val MISSION_RESULTS = mapOf(
    1 to MissionResult.ERROR,
    2 to MissionResult.UNSUPPORTED_FRAME,
    3 to MissionResult.UNSUPPORTED,
    4 to MissionResult.NO_SPACE,
    5 to MissionResult.INVALID,
    6 to MissionResult.INVALID_PARAM1,
    7 to MissionResult.INVALID_PARAM2,
    8 to MissionResult.INVALID_PARAM3,
    9 to MissionResult.INVALID_PARAM4,
    10 to MissionResult.INVALID_PARAM5_X,
    11 to MissionResult.INVALID_PARAM6_Y,
    12 to MissionResult.INVALID_PARAM7,
    14 to MissionResult.DENIED,
    15 to MissionResult.OPERATION_CANCELLED,
)

/** `MAV_MISSION_RESULT` (`common.xml`, mavlink/mavlink master). */
internal fun missionResultOf(type: Int): MissionResult = MISSION_RESULTS[type] ?: MissionResult.UNKNOWN
