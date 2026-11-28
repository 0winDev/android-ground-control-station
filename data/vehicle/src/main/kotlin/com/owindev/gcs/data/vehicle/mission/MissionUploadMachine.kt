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

        /** [highest] is the highest seq the vehicle asked for, [sent] whether its item was asked to be sent. */
        data class Transferring(val highest: Int, val sent: Boolean) : State

        /** The sender stopped for [reason]: nothing more is sent, and the vehicle is outwaited before the result. */
        data class Stopping(val reason: NotSentReason, val vehicleMayHold: OnBoardMission) : State

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
        is MissionAck -> onAck(state, message.type, message.missionType)
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

        is State.Transferring -> ended(UploadResult.NoAnswer(stoppedBy = null, partial(state)))

        is State.Stopping -> ended(UploadResult.NoAnswer(state.reason, state.vehicleMayHold))

        is State.Ended -> Step(state)
    }

    /** The sender refused the message [state]'s step asked for: it never left. */
    fun onSendRefused(state: State, reason: NotSentReason): Step = when (state) {
        is State.SendingCount -> if (state.attempt == 1) {
            ended(UploadResult.NotSent(UploadNotSentReason.Refused(reason)))
        } else {
            stopping(reason, OnBoardMission.PreviousCut(plan.count))
        }

        is State.Transferring -> stopping(reason, partial(state.copy(sent = false)))

        is State.Stopping, is State.Ended -> Step(state)
    }

    /** The sender stopped the upload for [reason] between sends. */
    fun onStop(state: State, reason: NotSentReason): Step = when (state) {
        is State.SendingCount -> stopping(reason, OnBoardMission.PreviousCut(plan.count))
        is State.Transferring -> stopping(reason, partial(state))
        is State.Stopping, is State.Ended -> Step(state)
    }

    private fun onRequest(state: State, seq: Int, missionType: Int): Step? {
        val stored = when (state) {
            is State.SendingCount -> OnBoardMission.PreviousCut(plan.count)
            is State.Transferring -> partial(state)
            is State.Stopping, is State.Ended -> return null
        }
        return when {
            missionType != MAV_MISSION_TYPE_MISSION ->
                ended(UploadResult.Failed(BadRequest.OtherMissionType(missionType), stored))

            seq !in plan.items.indices -> ended(UploadResult.Failed(BadRequest.OutOfRange(seq), stored))

            else -> {
                val highest = if (state is State.Transferring) maxOf(state.highest, seq) else seq
                val sent = if (state is State.Transferring && seq < state.highest) state.sent else true
                Step(State.Transferring(highest, sent), plan.items[seq], SILENCE_TIMEOUT)
            }
        }
    }

    private fun onAck(state: State, type: Int, missionType: Int): Step? {
        if (missionType != MAV_MISSION_TYPE_MISSION) return null
        return when (state) {
            is State.SendingCount -> when (type) {
                // A count is never accepted before its items, and it draws no INVALID_SEQUENCE: not this upload's.
                MAV_MISSION_ACCEPTED, MAV_MISSION_INVALID_SEQUENCE -> null

                // The vehicle gave up on a count that reached it, so it cut its mission (`MissionItemProtocol::update`).
                MAV_MISSION_OPERATION_CANCELLED ->
                    ended(
                        UploadResult.Rejected(
                            MissionResult.OPERATION_CANCELLED,
                            OnBoardMission.PreviousCut(plan.count),
                        ),
                    )

                // Refused before `truncate` (`handle_mission_count`).
                else -> ended(UploadResult.Rejected(missionResultOf(type), OnBoardMission.Previous))
            }

            is State.Transferring -> when {
                type == MAV_MISSION_ACCEPTED ->
                    if (state.highest == plan.count - 1 && state.sent) ended(UploadResult.Accepted) else null

                // ArduPilot answers an item it did not ask for with it and goes on (`MissionItemProtocol.cpp`).
                type == MAV_MISSION_INVALID_SEQUENCE -> Step(state, wait = SILENCE_TIMEOUT)

                else -> ended(
                    UploadResult.Rejected(
                        missionResultOf(type),
                        OnBoardMission.Partial(state.highest..state.highest, plan.count),
                    ),
                )
            }

            is State.Stopping, is State.Ended -> null
        }
    }

    /** Asking for seq [State.Transferring.highest] means every item before it is stored; the one sent may be too. */
    private fun partial(state: State.Transferring): OnBoardMission.Partial {
        val atMost = if (state.sent) state.highest + 1 else state.highest
        return OnBoardMission.Partial(state.highest..atMost, plan.count)
    }

    private fun stopping(reason: NotSentReason, vehicleMayHold: OnBoardMission) =
        Step(State.Stopping(reason, vehicleMayHold), wait = SILENCE_TIMEOUT)

    private fun ended(result: UploadResult) = Step(State.Ended(result))

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
