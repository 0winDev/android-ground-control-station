package com.owindev.gcs.data.vehicle.command

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Each command runs in the application [scope], so cancelling its caller never stops it or its retries. Results are
 * logged with the system ID, never the vehicle's address (MASVS-PRIVACY).
 */
@Singleton
internal class DefaultCommandRepository @Inject constructor(
    private val exchange: CommandExchange,
    private val takeOff: TakeOffSequence,
    private val altitudeRule: TakeOffAltitudeRule,
    @param:ApplicationScope private val scope: CoroutineScope,
) : CommandRepository {

    private val lock = Any()

    /** The command sent or about to be; guarded by [lock]. */
    private var active: Request? = null

    /**
     * Each command whose late answer is still listened for after no answer, or a superseded take off's (ADR 0008), by
     * command, to its request or that take off's own token; guarded by [lock].
     */
    private val lateWindows = mutableMapOf<VehicleCommand, Any>()

    private val mutablePending = MutableStateFlow<PendingCommand?>(null)
    override val pending: StateFlow<PendingCommand?> = mutablePending.asStateFlow()

    override suspend fun arm(systemId: SystemId): CommandResult = submit(systemId, VehicleCommand.ARM, ARM_COMMAND)

    override suspend fun returnToLaunch(systemId: SystemId): CommandResult =
        submit(systemId, VehicleCommand.RETURN_TO_LAUNCH, RETURN_TO_LAUNCH_COMMAND)

    override suspend fun land(systemId: SystemId): CommandResult = submit(systemId, VehicleCommand.LAND, LAND_COMMAND)

    override suspend fun takeOff(systemId: SystemId, altitudeMetres: Float): CommandResult {
        if (!altitudeRule.isValid(altitudeMetres)) {
            val result = CommandResult.NotSent(NotSentReason.ALTITUDE_OUT_OF_RANGE)
            report(systemId, VehicleCommand.TAKE_OFF, result)
            return result
        }
        return submit(
            Request(systemId, VehicleCommand.TAKE_OFF) { onState, onResult ->
                takeOff.run(systemId, altitudeMetres, onState, onResult)
            },
        )
    }

    private suspend fun submit(systemId: SystemId, command: VehicleCommand, mavCommand: MavCommand) = submit(
        Request(systemId, command) { onState, onResult ->
            exchange.run(systemId, command.name, mavCommand, onState, onResult = onResult)
        },
    )

    private suspend fun submit(request: Request): CommandResult {
        request.job = scope.launch(start = CoroutineStart.LAZY) { run(request) }
        request.job.invokeOnCompletion { cause -> if (cause != null) request.result.completeExceptionally(cause) }
        when (val admission = synchronized(lock) { admit(request) }) {
            Admission.Refused -> {
                request.job.cancel()
                report(request.systemId, request.command, CommandResult.NotSent(NotSentReason.BUSY))
                return CommandResult.NotSent(NotSentReason.BUSY)
            }

            is Admission.Admitted -> admission.superseded?.let { superseded ->
                // Completed before the cancel, which would otherwise fail it.
                superseded.result.complete(CommandResult.Superseded)
                superseded.job.cancel()
                report(superseded.systemId, superseded.command, CommandResult.Superseded)
                admission.heldWindow?.let { token ->
                    scope.launch {
                        delay(CommandExchange.DANGEROUS_LATE_WINDOW)
                        synchronized(lock) { lateWindows.remove(superseded.command, token) }
                    }
                }
            }
        }
        request.job.start()
        return request.result.await()
    }

    /** Called holding [lock]. */
    private fun admit(request: Request): Admission {
        val current = active
        val supersedes = current == null || (request.command in INTERRUPTERS && current.command in INTERRUPTIBLE)
        if (request.command in lateWindows || !supersedes) return Admission.Refused
        active = request
        mutablePending.value = null
        // A take off's late answer could otherwise answer the next take off while it climbs: its window outlives it.
        val heldWindow = if (current?.command == VehicleCommand.TAKE_OFF && current.attempted) Any() else null
        heldWindow?.let { lateWindows[VehicleCommand.TAKE_OFF] = it }
        return Admission.Admitted(superseded = current, heldWindow = heldWindow)
    }

    private suspend fun run(request: Request) {
        try {
            request.action(
                { state ->
                    synchronized(lock) {
                        if (active === request) {
                            request.attempted = true
                            mutablePending.value = PendingCommand(request.systemId, request.command, state)
                        }
                    }
                },
                { result -> finish(request, result) },
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

    private fun finish(request: Request, result: CommandResult) {
        synchronized(lock) {
            if (active !== request) return
            active = null
            mutablePending.value = null
            if (result.leftWithoutAnswer) lateWindows[request.command] = request
        }
        request.result.complete(result)
        report(request.systemId, request.command, result)
    }

    private fun report(systemId: SystemId, command: VehicleCommand, result: CommandResult) {
        val line = "$command to system ${systemId.value}: $result"
        if (result == CommandResult.Accepted) Log.i(TAG, line) else Log.w(TAG, line)
    }

    /** One command from [submit] to its result; [job] is set before anyone else sees it. */
    private class Request(
        val systemId: SystemId,
        val command: VehicleCommand,
        val action: suspend (onState: (PendingState) -> Unit, onResult: (CommandResult) -> Unit) -> Unit,
    ) {
        lateinit var job: Job
        val result = CompletableDeferred<CommandResult>()

        /** An attempt was about to leave, or left; guarded by [lock]. */
        var attempted = false
    }

    private sealed interface Admission {
        data object Refused : Admission

        data class Admitted(val superseded: Request?, val heldWindow: Any?) : Admission
    }

    private companion object {
        const val TAG = "CommandRepository"

        /** RTL and land supersede RTL, land and take off, even while the vehicle works on it; arm never (ADR 0008). */
        val INTERRUPTERS = setOf(VehicleCommand.RETURN_TO_LAUNCH, VehicleCommand.LAND)
        val INTERRUPTIBLE = INTERRUPTERS + VehicleCommand.TAKE_OFF

        /** Ended after an attempt left and before an answer, so its late answer is still listened for (ADR 0008). */
        val CommandResult.leftWithoutAnswer: Boolean
            get() = this is CommandResult.NoAnswer ||
                (this is CommandResult.ModeNotChanged && modeChange is CommandResult.NoAnswer)
    }
}
