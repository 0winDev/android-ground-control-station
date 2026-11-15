package com.owindev.gcs.core.domain.vehicle.command

import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.vehicle.SystemId
import kotlinx.coroutines.flow.StateFlow

/**
 * Sends commands to the active vehicle one at a time, each ending in exactly one [CommandResult] (REQ-042, REQ-043).
 * Cancelling the caller stops only its wait for the result, never the command or its retries.
 */
interface CommandRepository {

    /** The command waiting for its answer, `null` when none is. */
    val pending: StateFlow<PendingCommand?>

    /**
     * Sent once, never re-sent, and only while no pre-flight item is red and every amber one is in [acknowledged]
     * (REQ-048), checked again right before it leaves.
     */
    suspend fun arm(systemId: SystemId, acknowledged: PreflightAcknowledgements): CommandResult

    /** Supersedes a pending RTL or land. */
    suspend fun returnToLaunch(systemId: SystemId): CommandResult

    /** Supersedes a pending RTL or land. */
    suspend fun land(systemId: SystemId): CommandResult

    /**
     * Puts the vehicle in GUIDED first when it is not, then takes off to [altitudeMetres] above home (REQ-044), only
     * while armed and no pre-flight item is red (REQ-048); neither step is re-sent, and RTL or land supersedes it at
     * any step. RTL and land are never refused by the pre-flight check.
     */
    suspend fun takeOff(systemId: SystemId, altitudeMetres: Float): CommandResult
}

enum class VehicleCommand {
    ARM,
    RETURN_TO_LAUNCH,
    LAND,
    TAKE_OFF,
}

data class PendingCommand(val systemId: SystemId, val command: VehicleCommand, val state: PendingState)

sealed interface PendingState {

    /** Sent for the [attempt]th time (from 1), with no answer yet. */
    data class Waiting(val attempt: Int) : PendingState

    /** The vehicle said it started; [percent] is its progress, `null` when it gave none. */
    data class InProgress(val percent: Int?) : PendingState
}
