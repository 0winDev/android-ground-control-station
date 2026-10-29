package com.owindev.gcs.data.vehicle.telemetry

import android.util.Log
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.isForThisGcs
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * Asks the active vehicle for its telemetry ([TELEMETRY_STREAMS]) each time it becomes CONNECTED
 * (REQ-023): on first contact and after every recovery, since a rebooted autopilot has forgotten the
 * intervals.
 *
 * After that round, and for as long as the vehicle stays CONNECTED, a watched stream that has been silent for its
 * [StalenessRule] threshold is requested again (REQ-023): when its value goes stale, the operator sees it
 * greyed out and the vehicle is asked for it. A stream is requested at most once every
 * [REQUEST_AGAIN_INTERVAL], so a vehicle that rejects or ignores it is not flooded. Silence is read from the
 * [TelemetryStore], which the vehicle repository's listener fills: this class never listens for telemetry
 * itself. A stream is only requested again while the vehicle is heard right now (the `isHeard` given to
 * [run]): never once the link is no longer listened to, since waiting for the answer would open the socket
 * again, and never after the device sleeps past the link-loss timeout before the watchdog has caught up.
 *
 * Each stream is one `MAV_CMD_SET_MESSAGE_INTERVAL` in a COMMAND_LONG to the autopilot component
 * (`MAV_COMP_ID_AUTOPILOT1`), sent one at a time: the COMMAND_ACK only names the command, not the message
 * it was about, so the answer is taken to be the next final ACK for this command from the autopilot, at
 * the vehicle's address, to this GCS. It is not sent to component 0 (all components), which could bring
 * answers from other components.
 *
 * Known limits, acceptable because the results only feed the log:
 * - an ACK that arrives after [ACK_TIMEOUT] is taken as the answer to the next request, shifting the
 *   results by one. Whether a stream really flows is shown by the stream itself, by the age of its value;
 *   the ACK is never the proof;
 * - the command protocol says "If no acknowledgement is received the command must be automatically
 *   re-sent" (https://mavlink.io/en/services/command.html). Not as such: a stream that does not arrive is
 *   requested again, whatever its ACK said, and the command service with retries comes in v0.4;
 * - Android blocks a background app's network about 5 s after it leaves the screen (#14), while the link
 *   is still listened to: the streams then go silent and their requests fail ([StreamResult.NotSent]); only
 *   that change of result is logged, and nothing leaves the device;
 * - the ACK is unsigned until v0.6, so a forged one can hide a rejection (`docs/security.md`, N-2).
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
    private val store: TelemetryStore,
    private val rule: StalenessRule,
    private val timeSource: TimeSource.WithComparableMarks,
) {

    /** The last result of each stream, by message ID; only touched by one round at a time. */
    private val lastResults = mutableMapOf<Int, StreamResult>()

    /**
     * Requests every stream each time [targets] gives a vehicle, and then each one again whenever it goes
     * silent while [isHeard] says the vehicle is still heard right now, until cancelled. `null` means no
     * vehicle to ask (none active, or the active one is not CONNECTED); whatever is in progress is cancelled
     * then.
     */
    suspend fun run(targets: Flow<TelemetryTarget?>, isHeard: () -> Boolean) {
        targets.collectLatest { target ->
            if (target != null) {
                requestAll(target)
                requestAgainWhenSilent(target, isHeard)
            }
        }
    }

    /** Requests every stream from [target], one after the other, and returns their results in order. */
    suspend fun requestAll(target: TelemetryTarget): List<StreamResult> =
        TELEMETRY_STREAMS.map { stream -> request(target, stream).also { result -> report(stream, result) } }

    /** The streams whose silence is watched, with the part of the telemetry that tells it. */
    private val watched: Map<TelemetryStream, TelemetryKind> =
        TELEMETRY_STREAMS.mapNotNull { stream -> stream.kind?.let { kind -> stream to kind } }.toMap()

    /**
     * Until cancelled, requests each watched stream again once it has been silent for its staleness threshold and
     * [REQUEST_AGAIN_INTERVAL] has passed since it was last requested. A stream never received counts as
     * silent from the start. Sleeps until the next stream is due and then reads the store again, since a
     * message that arrived in the meantime moves that stream's turn later. A turn that comes while the vehicle
     * is not [isHeard] is skipped, not sent, and waits a whole interval again.
     */
    private suspend fun requestAgainWhenSilent(target: TelemetryTarget, isHeard: () -> Boolean): Nothing {
        val requestedAt = watched.keys.associateWithTo(mutableMapOf()) { timeSource.markNow() }
        while (true) {
            val untilDue = watched.mapValues { (stream, kind) -> untilDue(target, kind, requestedAt.getValue(stream)) }
            val due = untilDue.entries.firstOrNull { it.value <= Duration.ZERO }?.key
            if (due == null) {
                delay(untilDue.values.min())
            } else {
                requestedAt[due] = timeSource.markNow()
                if (isHeard()) report(due, request(target, due))
            }
        }
    }

    /** How long until the stream of [kind] is due to be requested again; zero or less when it is due now. */
    private fun untilDue(target: TelemetryTarget, kind: TelemetryKind, requestedAt: TimeMark): Duration {
        val silence = store.telemetry.value[target.systemId]?.get(kind)?.receivedAt?.elapsedNow()
        val untilStale = if (silence == null) Duration.ZERO else rule.thresholdOf(kind) - silence
        val untilAllowed = REQUEST_AGAIN_INTERVAL - requestedAt.elapsedNow()
        return maxOf(untilStale, untilAllowed)
    }

    private suspend fun request(target: TelemetryTarget, stream: TelemetryStream): StreamResult = coroutineScope {
        // Listening before sending, so an answer faster than the send's return is not missed.
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(ACK_TIMEOUT) { session.messages.first { it.isFinalAckFrom(target) } }
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

    private fun ReceivedMessage.isFinalAckFrom(target: TelemetryTarget): Boolean {
        val ack = message as? CommandAck ?: return false
        return systemId == target.systemId.value &&
            componentId == MAV_COMP_ID_AUTOPILOT1 &&
            sender == target.address &&
            ack.command == MAV_CMD_SET_MESSAGE_INTERVAL &&
            ack.result != MAV_RESULT_IN_PROGRESS &&
            ack.isForThisGcs()
    }

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
        /**
         * How long to wait for each request's COMMAND_ACK. Measured in SITL (#36, ArduCopter V4.7.2-beta over
         * loopback): every answer within 6–27 ms over 10 requests. 1 s is about 37 times the worst of those,
         * room for a radio link's round trip, and keeps a round of seven requests with no answers to 7 s.
         *
         * Not extended after `MAV_RESULT_IN_PROGRESS`, as the command protocol advises for long-running
         * commands ("the GCS should have a much increased timeout"): `MAV_CMD_SET_MESSAGE_INTERVAL` is not one.
         * Do not copy this into a command service.
         */
        val ACK_TIMEOUT: Duration = 1.seconds

        /**
         * The shortest time between two requests for the same stream (#39). Each is a 45-byte COMMAND_LONG, so
         * a stream the vehicle never sends costs 9 B/s, under 1 % of the budget in [TELEMETRY_STREAMS].
         */
        val REQUEST_AGAIN_INTERVAL: Duration = 5.seconds

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
