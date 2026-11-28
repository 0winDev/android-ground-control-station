package com.owindev.gcs.core.domain.route.upload

import com.owindev.gcs.core.domain.vehicle.command.NotSentReason

/** How an upload ended: exactly one per upload (REQ-056, ADR 0010). Every failure says what the vehicle may hold. */
sealed interface UploadResult {

    /** The vehicle stored every item and said so. */
    data object Accepted : UploadResult

    /** The vehicle refused the count or an item with [result]. */
    data class Rejected(val result: MissionResult, val vehicleMayHold: OnBoardMission) : UploadResult

    /** The vehicle asked for something this upload cannot answer, so it ended. */
    data class Failed(val request: BadRequest, val vehicleMayHold: OnBoardMission) : UploadResult

    /**
     * No answer once the vehicle's own timeout had passed; [stoppedBy] is why the sender stopped early, `null` when it
     * waited it out.
     */
    data class NoAnswer(val stoppedBy: NotSentReason?, val vehicleMayHold: OnBoardMission) : UploadResult

    /** Nothing left this GCS: the vehicle keeps its previous mission. */
    data class NotSent(val reason: UploadNotSentReason) : UploadResult
}

/**
 * What the vehicle may hold after a failed upload, read in ArduPilot's code (ADR 0010). Items are counted as the
 * vehicle holds them, home as item 0.
 */
sealed interface OnBoardMission {

    /** Its mission from before the upload, untouched. */
    data object Previous : OnBoardMission

    /** Its mission from before, cut to its first [count] items if the count reached it. */
    data class PreviousCut(val count: Int) : OnBoardMission

    /**
     * The route's first items, as many as one of [routeItems] says, then what is left of the previous mission up to
     * [count]; `routeItems.last == count` is the whole route.
     */
    data class Partial(val routeItems: IntRange, val count: Int) : OnBoardMission
}

/** Why the vehicle refused an upload, from `MAV_MISSION_RESULT` (`common.xml`). */
enum class MissionResult {
    ERROR,
    UNSUPPORTED_FRAME,
    UNSUPPORTED,
    NO_SPACE,
    INVALID,
    INVALID_PARAM1,
    INVALID_PARAM2,
    INVALID_PARAM3,
    INVALID_PARAM4,
    INVALID_PARAM5_X,
    INVALID_PARAM6_Y,
    INVALID_PARAM7,
    DENIED,
    OPERATION_CANCELLED,
    UNKNOWN,
}

/** A request from the vehicle that ends an upload. */
sealed interface BadRequest {

    /** For item [seq], at or past the count. */
    data class OutOfRange(val seq: Int) : BadRequest

    /** For a plan of another `MAV_MISSION_TYPE` than the mission. */
    data class OtherMissionType(val missionType: Int) : BadRequest
}

/** Why an upload never left. */
sealed interface UploadNotSentReason {

    /** The items built from the route break the mission protocol's rules: a bug, never the operator's doing. */
    data object InvalidPlan : UploadNotSentReason

    /** The sender's checks refused the first count (#152). */
    data class Refused(val reason: NotSentReason) : UploadNotSentReason
}
