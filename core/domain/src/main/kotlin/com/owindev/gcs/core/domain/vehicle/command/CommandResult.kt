package com.owindev.gcs.core.domain.vehicle.command

/** How a command ended: exactly one per command (REQ-042). */
sealed interface CommandResult {

    data object Accepted : CommandResult

    /** The vehicle cannot execute it now and says waiting should fix it (`MAV_RESULT_TEMPORARILY_REJECTED`). */
    data object Busy : CommandResult

    /** The vehicle refused it, with its own explanation as [reason] when it sent one. */
    data class Rejected(val rejection: Rejection, val reason: String?) : CommandResult

    /**
     * No answer after [attempts] sends. The command may have taken effect whatever the flags say, so the operator
     * checks the vehicle's state: [startedByVehicle] only means the vehicle said it started, and [retriesStopped] why
     * no more attempts were made, `null` when all of them were.
     */
    data class NoAnswer(val attempts: Int, val startedByVehicle: Boolean, val retriesStopped: NotSentReason?) :
        CommandResult

    /** Stopped for a newer RTL or land the operator sent. */
    data object Superseded : CommandResult

    /** Never left this GCS. */
    data class NotSent(val reason: NotSentReason) : CommandResult
}

/** Why the vehicle refused a command, from `MAV_RESULT` (`common.xml`). */
enum class Rejection {
    DENIED,
    UNSUPPORTED,
    FAILED,
    CANCELLED,
    COMMAND_LONG_ONLY,
    COMMAND_INT_ONLY,
    UNSUPPORTED_FRAME,
    NOT_IN_CONTROL,
    UNKNOWN,
}

/** Why a command never left (REQ-043). */
enum class NotSentReason {
    /** A recording plays: nothing reaches a vehicle. */
    REPLAY,

    /** The system ID is not the active vehicle's, or there is none. */
    NOT_ACTIVE_VEHICLE,

    /** The active vehicle is not heard now. */
    LINK_LOST,

    /** The vehicle's HEARTBEATs come from another address than its first one. */
    ROUTE_CHANGED,

    /**
     * Another command is pending, or this one ended without answer moments ago and its late answer is still listened
     * for (ADR 0008); `pending` shows the first case only.
     */
    BUSY,

    SEND_FAILED,
}
