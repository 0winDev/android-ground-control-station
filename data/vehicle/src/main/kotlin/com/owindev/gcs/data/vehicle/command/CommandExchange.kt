package com.owindev.gcs.data.vehicle.command

import android.util.Log
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.link.isForThisGcs
import java.net.InetSocketAddress
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ACK carries no sequence number (https://mavlink.io/en/services/command.html), so a matching ACK to an earlier
 * attempt also answers. Once an attempt has left, the command ends as no answer, never as not sent: the vehicle may
 * have executed it.
 */
internal class CommandExchange @Inject constructor(
    private val sender: GcsMessageSender,
    private val session: LinkSession,
    private val routes: CommandRoutes,
) {

    /** After no answer, returns only after the late window, dropping any answer meanwhile (ADR 0008). */
    suspend fun run(
        systemId: SystemId,
        command: VehicleCommand,
        onState: (PendingState) -> Unit,
        onResult: (CommandResult) -> Unit,
    ) {
        val current = session.session.value
        when (val route = routes.routeTo(systemId)) {
            is CommandRoute.Closed -> onResult(CommandResult.NotSent(route.reason))

            is CommandRoute.Open -> coroutineScope {
                val request = Request(systemId, command, onState)
                val acks = answersTo(request, route.address, current)
                val result = attempts(request, acks)
                onResult(result)
                if (result is CommandResult.NoAnswer) dropLateAnswers(request, acks)
                coroutineContext.cancelChildren()
            }
        }
    }

    /** Started before the first attempt is sent, so an answer faster than the send's return is not missed. */
    private fun CoroutineScope.answersTo(
        request: Request,
        address: InetSocketAddress,
        current: SessionId,
    ): ReceiveChannel<CommandAck> {
        val acks = Channel<CommandAck>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.messages.collect { received ->
                if (received.answers(request, address, current)) acks.trySend(received.message as CommandAck)
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.session.first { it != current }
            acks.close()
        }
        return acks
    }

    private fun ReceivedMessage.answers(request: Request, address: InetSocketAddress, current: SessionId): Boolean {
        val ack = message as? CommandAck ?: return false
        return session == current &&
            systemId == request.systemId.value &&
            componentId == MAV_COMP_ID_AUTOPILOT1 &&
            sender == address &&
            ack.command == request.command.id &&
            ack.isForThisGcs()
    }

    private suspend fun attempts(request: Request, acks: ReceiveChannel<CommandAck>): CommandResult {
        val attempts = request.command.attempts
        for (attempt in 1..attempts) {
            request.onState(PendingState.Waiting(attempt))
            val result = attempt(request, attempt, acks)
            if (result != null) return result
        }
        return noAnswer(attempts, retriesStopped = null)
    }

    /** Sends the [attempt]th time and waits [ACK_TIMEOUT]: `null` when no answer came in time. */
    private suspend fun attempt(request: Request, attempt: Int, acks: ReceiveChannel<CommandAck>): CommandResult? {
        val sent = sender.send(commandLongOf(request.systemId, request.command, confirmation = attempt - 1)) {
            routes.routeTo(request.systemId)
        }
        val received = if (sent == CommandSendResult.Sent) {
            withTimeoutOrNull(ACK_TIMEOUT) { acks.receiveCatching() }
        } else {
            null
        }
        val ack = received?.getOrNull()
        return when {
            sent is CommandSendResult.NotSent ->
                if (attempt == 1) CommandResult.NotSent(sent.reason) else noAnswer(attempt - 1, sent.reason)

            received == null -> null

            // The session changed: nothing more can answer.
            ack == null -> noAnswer(attempt, sessionEnded())

            ack.isInProgress -> awaitAfterInProgress(request, ack, acks, attempt)

            else -> ack.toResult()
        }
    }

    /** [IN_PROGRESS_TIMEOUT] counts from the first IN_PROGRESS, never extended by the next ones. */
    private suspend fun awaitAfterInProgress(
        request: Request,
        first: CommandAck,
        acks: ReceiveChannel<CommandAck>,
        attempts: Int,
    ): CommandResult {
        var closed = false
        val final = withTimeoutOrNull(IN_PROGRESS_TIMEOUT) {
            var ack: CommandAck? = first
            while (ack != null && ack.isInProgress) {
                request.onState(PendingState.InProgress(ack.percent))
                val next = acks.receiveCatching()
                closed = next.isClosed
                ack = next.getOrNull()
            }
            ack
        }
        val retriesStopped = if (closed) sessionEnded() else null
        return final?.toResult() ?: CommandResult.NoAnswer(attempts, startedByVehicle = true, retriesStopped)
    }

    private fun noAnswer(attempts: Int, retriesStopped: NotSentReason?) =
        CommandResult.NoAnswer(attempts, startedByVehicle = false, retriesStopped)

    private fun sessionEnded(): NotSentReason =
        if (session.session.value.origin == DataOrigin.REPLAY) NotSentReason.REPLAY else NotSentReason.LINK_LOST

    private suspend fun dropLateAnswers(request: Request, acks: ReceiveChannel<CommandAck>) {
        val window = if (request.command == VehicleCommand.ARM) ARM_LATE_WINDOW else LATE_WINDOW
        withTimeoutOrNull(window) {
            for (ack in acks) {
                Log.w(TAG, "${request.command} to system ${request.systemId.value}: late answer ${ack.result} dropped")
            }
        }
    }

    private class Request(val systemId: SystemId, val command: VehicleCommand, val onState: (PendingState) -> Unit)

    companion object {
        /** Per attempt (ADR 0008): SITL answered in 6–27 ms (#36), with room for a radio link's round trip. */
        val ACK_TIMEOUT: Duration = 1.5.seconds

        /** From the first `MAV_RESULT_IN_PROGRESS` to the final ACK (ADR 0008). */
        val IN_PROGRESS_TIMEOUT: Duration = 10.seconds

        /** How long a command without answer is still listened for, and the same command refused (ADR 0008). */
        val LATE_WINDOW: Duration = 1.5.seconds

        /**
         * Arm's late window (ADR 0008): an earlier arm's late FAILED taken as a new arm's answer would read "rejected"
         * while the new one arms the vehicle. Refusing a new arm for 10 s covers round trips up to 11.5 s.
         */
        val ARM_LATE_WINDOW: Duration = 10.seconds

        private const val TAG = "CommandExchange"
    }
}
