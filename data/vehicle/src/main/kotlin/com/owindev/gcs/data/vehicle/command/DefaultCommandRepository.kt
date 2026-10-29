package com.owindev.gcs.data.vehicle.command

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs one [CommandExchange] at a time (REQ-042, ADR 0008), each as a job of the application [scope]: its caller only
 * awaits the result. A new RTL or land supersedes a pending RTL or land; arm never supersedes nor is superseded; a
 * command is refused while its own late answer is still listened for, any other is not.
 *
 * Every result is logged once, with the system ID and never the vehicle's address (MASVS-PRIVACY).
 */
@Singleton
internal class DefaultCommandRepository @Inject constructor(
    private val exchange: CommandExchange,
    @param:ApplicationScope private val scope: CoroutineScope,
) : CommandRepository {

    private val lock = Any()

    /** The command sent or about to be; guarded by [lock]. */
    private var active: Request? = null

    /** Each command whose late answer is still listened for after no answer, by command; guarded by [lock]. */
    private val lateWindows = mutableMapOf<VehicleCommand, Request>()

    private val mutablePending = MutableStateFlow<PendingCommand?>(null)
    override val pending: StateFlow<PendingCommand?> = mutablePending.asStateFlow()

    override suspend fun arm(systemId: SystemId): CommandResult = submit(systemId, VehicleCommand.ARM)

    override suspend fun returnToLaunch(systemId: SystemId): CommandResult =
        submit(systemId, VehicleCommand.RETURN_TO_LAUNCH)

    override suspend fun land(systemId: SystemId): CommandResult = submit(systemId, VehicleCommand.LAND)

    private suspend fun submit(systemId: SystemId, command: VehicleCommand): CommandResult {
        val request = Request(systemId, command)
        request.job = scope.launch(start = CoroutineStart.LAZY) { run(request) }
        request.job.invokeOnCompletion { cause -> if (cause != null) request.result.completeExceptionally(cause) }
        when (val admission = synchronized(lock) { admit(request) }) {
            Admission.Refused -> {
                request.job.cancel()
                report(request, CommandResult.NotSent(NotSentReason.BUSY))
                return CommandResult.NotSent(NotSentReason.BUSY)
            }

            is Admission.Admitted -> admission.superseded?.let { superseded ->
                // Completed before the cancel, which would otherwise fail it.
                superseded.result.complete(CommandResult.Superseded)
                superseded.job.cancel()
                report(superseded, CommandResult.Superseded)
            }
        }
        request.job.start()
        return request.result.await()
    }

    /**
     * Makes [request] the active command unless another is pending that it cannot supersede, or the same command's late
     * answer is still listened for. Called holding [lock].
     */
    private fun admit(request: Request): Admission {
        val current = active
        val supersedes = current == null || (current.command.givesWay && request.command.givesWay)
        if (request.command in lateWindows || !supersedes) return Admission.Refused
        active = request
        mutablePending.value = null
        return Admission.Admitted(superseded = current)
    }

    /** RTL and land give way to one another; arm never does (ADR 0008). */
    private val VehicleCommand.givesWay: Boolean
        get() = this != VehicleCommand.ARM

    private suspend fun run(request: Request) {
        try {
            exchange.run(
                systemId = request.systemId,
                command = request.command,
                onState = { state -> publish(request, state) },
                onResult = { result -> finish(request, result) },
            )
        } finally {
            synchronized(lock) {
                lateWindows.remove(request.command, request)
                // Only if the exchange failed before its result: never leave every later command refused as busy.
                if (active === request) {
                    active = null
                    mutablePending.value = null
                }
            }
        }
    }

    private fun publish(request: Request, state: PendingState) = synchronized(lock) {
        if (active === request) mutablePending.value = PendingCommand(request.systemId, request.command, state)
    }

    private fun finish(request: Request, result: CommandResult) {
        synchronized(lock) {
            if (active !== request) return
            active = null
            mutablePending.value = null
            if (result is CommandResult.NoAnswer) lateWindows[request.command] = request
        }
        request.result.complete(result)
        report(request, result)
    }

    private fun report(request: Request, result: CommandResult) {
        val line = "${request.command} to system ${request.systemId.value}: $result"
        if (result == CommandResult.Accepted) Log.i(TAG, line) else Log.w(TAG, line)
    }

    /** One command from [submit] to its result; [job] is set before anyone else sees it. */
    private class Request(val systemId: SystemId, val command: VehicleCommand) {
        lateinit var job: Job
        val result = CompletableDeferred<CommandResult>()
    }

    private sealed interface Admission {
        data object Refused : Admission

        data class Admitted(val superseded: Request?) : Admission
    }

    private companion object {
        const val TAG = "CommandRepository"
    }
}
