package com.owindev.gcs.data.vehicle.telemetry

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender.Companion.GCS_COMPONENT_ID
import com.owindev.gcs.data.vehicle.link.GcsMessageSender.Companion.GCS_SYSTEM_ID
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The vehicle to request telemetry from, and the address its HEARTBEATs come from. */
internal data class TelemetryTarget(val systemId: SystemId, val address: InetSocketAddress)

/** What came of requesting one stream. */
internal sealed interface StreamResult {
    /** `MAV_RESULT_ACCEPTED`: the vehicle streams the message at the requested interval. */
    data object Accepted : StreamResult

    /** Any final `MAV_RESULT` other than ACCEPTED, as the raw value from the COMMAND_ACK. */
    data class Rejected(val result: Int) : StreamResult

    /** No COMMAND_ACK within [TelemetryRequester.ACK_TIMEOUT]. */
    data object NoAck : StreamResult

    /** The request never left: the link was closed or the send failed. */
    data object NotSent : StreamResult
}

/**
 * Asks the active vehicle for the HUD's telemetry ([TELEMETRY_STREAMS]) each time it becomes CONNECTED
 * (REQ-023): on first contact and after every recovery, since a rebooted autopilot has forgotten the
 * intervals.
 *
 * Each stream is one `MAV_CMD_SET_MESSAGE_INTERVAL` in a COMMAND_LONG to the autopilot component
 * (`MAV_COMP_ID_AUTOPILOT1`), sent one at a time: the COMMAND_ACK only names the command, not the message
 * it was about, so with one request in flight the next final ACK from the autopilot is its answer. It is
 * not sent to component 0 (all components), which could bring answers from other components. There is no
 * retry (v0.4): a stream that does not arrive is requested again once telemetry has an age (#39).
 *
 * Only the ACK's `result` is read. Its `progress` reads 0 when the sender omits the extensions, which is
 * not a real value (#35), and `MAV_RESULT_IN_PROGRESS` is not final, so it is skipped while waiting.
 *
 * A warning is logged only when a stream's result changes to something other than ACCEPTED, and a line
 * when it is accepted again, as the link repository does (#25): never once per round, never with the
 * peer's address.
 */
@Singleton
internal class TelemetryRequester @Inject constructor(
    private val sender: GcsMessageSender,
    private val session: LinkSession,
) {

    /** The last result of each stream, by message ID; only touched by one round at a time. */
    private val lastResults = mutableMapOf<Int, StreamResult>()

    /**
     * Requests every stream each time [targets] gives a vehicle, until cancelled. `null` means no vehicle
     * to ask (none active, or the active one is not CONNECTED); a round in progress is cancelled then.
     */
    suspend fun run(targets: Flow<TelemetryTarget?>) {
        targets.collectLatest { target -> if (target != null) requestAll(target) }
    }

    /** Requests every stream from [target], one after the other, and returns their results in order. */
    suspend fun requestAll(target: TelemetryTarget): List<StreamResult> =
        TELEMETRY_STREAMS.map { stream -> request(target, stream).also { result -> report(stream, result) } }

    private suspend fun request(target: TelemetryTarget, stream: TelemetryStream): StreamResult = coroutineScope {
        // Listening before sending, so an answer faster than the send's return is not missed.
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(ACK_TIMEOUT) { session.messages.first { it.isFinalAckFrom(target.systemId) } }
        }
        when (sender.send(setMessageInterval(target.systemId, stream), target.address)) {
            SendResult.Sent -> ack.await()?.let { received -> resultOf(received.message as CommandAck) }
                ?: StreamResult.NoAck

            SendResult.NotOpen, is SendResult.Failed -> {
                ack.cancel()
                StreamResult.NotSent
            }
        }
    }

    private fun ReceivedMessage.isFinalAckFrom(systemId: SystemId): Boolean {
        val ack = message as? CommandAck ?: return false
        return this.systemId == systemId.value &&
            componentId == MAV_COMP_ID_AUTOPILOT1 &&
            ack.command == MAV_CMD_SET_MESSAGE_INTERVAL &&
            ack.result != MAV_RESULT_IN_PROGRESS &&
            ack.isForThisGcs()
    }

    /**
     * Addressed to this GCS, or without target: the targets are extension fields, which an older sender
     * omits and which then read as 0.
     */
    private fun CommandAck.isForThisGcs(): Boolean =
        (targetSystem == GCS_SYSTEM_ID && targetComponent == GCS_COMPONENT_ID) ||
            (targetSystem == 0 && targetComponent == 0)

    private fun resultOf(ack: CommandAck): StreamResult = when (ack.result) {
        MAV_RESULT_ACCEPTED -> StreamResult.Accepted
        else -> StreamResult.Rejected(ack.result)
    }

    private fun report(stream: TelemetryStream, result: StreamResult) {
        val previous = lastResults.put(stream.messageId, result)
        when {
            result == previous -> Unit
            result != StreamResult.Accepted -> Log.w(TAG, "Telemetry stream ${stream.messageId} not set: $result")
            previous != null -> Log.i(TAG, "Telemetry stream ${stream.messageId} accepted again")
        }
    }

    private fun setMessageInterval(systemId: SystemId, stream: TelemetryStream) = CommandLong(
        targetSystem = systemId.value,
        targetComponent = MAV_COMP_ID_AUTOPILOT1,
        command = MAV_CMD_SET_MESSAGE_INTERVAL,
        confirmation = 0,
        param1 = stream.messageId.toFloat(),
        param2 = stream.interval.inWholeMicroseconds.toFloat(),
        // "By default assumed not used (0)"; param7 0 is "Flight-stack default (recommended)" (common.xml).
        param3 = 0f,
        param4 = 0f,
        param5 = 0f,
        param6 = 0f,
        param7 = 0f,
    )

    companion object {
        /** How long to wait for each request's COMMAND_ACK. */
        val ACK_TIMEOUT: Duration = 1.seconds

        /** `MAV_CMD_SET_MESSAGE_INTERVAL` (`common.xml`): param1 message ID, param2 interval in µs. */
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511

        /** `MAV_COMP_ID_AUTOPILOT1` (`minimal.xml`). */
        const val MAV_COMP_ID_AUTOPILOT1 = 1

        // MAV_RESULT (common.xml). IN_PROGRESS "must terminate by sending a COMMAND_ACK message with final
        // result of the operation".
        private const val MAV_RESULT_ACCEPTED = 0
        private const val MAV_RESULT_IN_PROGRESS = 5
        private const val TAG = "TelemetryRequester"
    }
}
