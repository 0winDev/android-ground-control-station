package com.owindev.gcs.core.domain.vehicle.command

import com.owindev.gcs.core.domain.vehicle.SystemId
import kotlinx.coroutines.flow.StateFlow

/**
 * Sends commands to the active vehicle, one at a time, each ending in exactly one [CommandResult] (REQ-042). Nothing is
 * sent during a replay, to another system ID, while the link is lost or over a changed route (REQ-043). Implemented in
 * `:data:vehicle` and bound there with Hilt.
 *
 * A command runs on its own, in the application's scope: cancelling the caller stops the wait for the result, never the
 * command or its retries.
 */
interface CommandRepository {

    /** The command waiting for its answer, `null` when none is. */
    val pending: StateFlow<PendingCommand?>

    /** Arms the vehicle's motors, unless its safety checks prevent it; sent once, never re-sent. */
    suspend fun arm(systemId: SystemId): CommandResult

    /** Switches the vehicle to RTL; supersedes a pending RTL or land. */
    suspend fun returnToLaunch(systemId: SystemId): CommandResult

    /** Switches the vehicle to LAND; supersedes a pending RTL or land. */
    suspend fun land(systemId: SystemId): CommandResult
}

/** The commands the operator can send. */
enum class VehicleCommand {
    ARM,
    RETURN_TO_LAUNCH,
    LAND,
}

/** A command that has been sent and waits for its answer. */
data class PendingCommand(val systemId: SystemId, val command: VehicleCommand, val state: PendingState)

/** Where a pending command stands. */
sealed interface PendingState {

    /** Sent for the [attempt]th time (from 1), with no answer yet. */
    data class Waiting(val attempt: Int) : PendingState

    /** The vehicle said it started; [percent] is its progress, `null` when it gave none. */
    data class InProgress(val percent: Int?) : PendingState
}
