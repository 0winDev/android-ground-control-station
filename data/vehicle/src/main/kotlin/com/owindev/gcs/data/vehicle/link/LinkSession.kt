package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.HeartbeatMapper
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * How long the repositories keep observing the session after their last observer leaves: keeps the link
 * open across short gaps, such as a configuration change.
 */
internal val LINK_STOP_TIMEOUT: Duration = 5.seconds

/**
 * Backoff before reopening the link after a transport failure while it is observed (REQ-019): doubles from
 * [LINK_RETRY_FIRST] up to [LINK_RETRY_MAX], and starts again from the first delay only once the link has
 * recovered ([LINK_STABLE_AFTER]).
 */
internal val LINK_RETRY_FIRST: Duration = 1.seconds
internal val LINK_RETRY_MAX: Duration = 5.seconds

/**
 * How long a reopened socket must keep working, from its first datagram, before the link counts as recovered
 * (REQ-019). Twice [LINK_RETRY_MAX], so a link that keeps failing every few seconds never looks recovered: its
 * backoff keeps growing, its outage is logged once and the HUD shows it as unstable instead of flickering.
 */
internal val LINK_STABLE_AFTER: Duration = LINK_RETRY_MAX * 2

/**
 * The one place that receives, parses and decodes the vehicle link; every repository builds on it.
 *
 * The [live] source (the UDP transport) can only be collected once (a second collection would bind the port twice
 * and fail), so it is shared in the application scope. The socket stays open while anyone collects [messages] or
 * [whileOpen] (each repository already waits [LINK_STOP_TIMEOUT] before letting go); after that it
 * closes and [status] goes back to `Running(0)`; the next observer starts a new session with a new
 * socket and a new parser (it keeps partial frames between datagrams).
 *
 * While observed, a failed transport is reopened by itself after a bounded backoff ([LINK_RETRY_FIRST] to
 * [LINK_RETRY_MAX]), so HEARTBEATs that resume are read again without restarting the app (REQ-019). A
 * transport that ends without an error is treated the same way. The only failure never retried is the
 * session's first bind failing ([LinkStatus.Unavailable]): another app holds the port. A bind failure
 * while reopening is retried like any other failure, and told apart as [LinkStatus.UnavailableRetrying].
 *
 * After a failure the link is [LinkStatus.Failed] until datagrams arrive again, then
 * [LinkStatus.Recovering] until a socket has worked for [LINK_STABLE_AFTER], measured on [timeSource], and
 * only then [LinkStatus.Running] again. A socket that fails after delivering datagrams leaves the link
 * Recovering, so a flapping link shows one steady state instead of one change per datagram; but only for
 * [LINK_STABLE_AFTER] without datagrams, after which it is [LinkStatus.Failed] again: a reopened socket that
 * stays silent is an outage, not an unstable link.
 *
 * [messages] has no replay: a late subscriber only gets messages that arrive after it subscribed, so an
 * old HEARTBEAT is never delivered as a new one. What a late subscriber needs to know, such as a bind
 * failure, is in [status].
 *
 * The first autopilot HEARTBEAT it decodes pins the [activeVehicle] (REQ-020), before any reader sees it. Every frame
 * it delimits, unknown message IDs included, then goes to the [recorder] as it arrives (REQ-036), and the recording
 * closes with the session.
 */
@Singleton
internal class LinkSession @Inject constructor(
    private val live: DatagramSource,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks,
    private val recorder: FlightRecorder,
    private val activeVehicle: ActiveVehicle,
) {

    private val mutableStatus = MutableStateFlow<LinkStatus>(LinkStatus.Running(datagrams = 0))

    /**
     * The state of the current session; it only changes while the session is open. The datagram count runs
     * for the whole session, across reopenings of the socket.
     */
    val status: StateFlow<LinkStatus> = mutableStatus.asStateFlow()

    // Built on first use, so the source is only asked for its flow once someone observes the link.
    val messages: SharedFlow<ReceivedMessage> by lazy {
        flow {
            var datagrams = 0L
            var retryDelay = LINK_RETRY_FIRST
            var reopening = false
            // From a failure until a socket has worked for LINK_STABLE_AFTER.
            var recovering = false
            // Ends a Recovering held without datagrams; cancelled by the next datagram.
            var holdLimit: Job? = null
            // Only to launch holdLimit: every emit still happens in this flow's own coroutine.
            coroutineScope {
                while (true) {
                    // A new parser per socket: a frame split across two sockets is not one frame.
                    val parser = MavlinkParser(MavlinkMessages)
                    var failure: IOException = EOFException("The transport stopped delivering datagrams")
                    var upSince: ComparableTimeMark? = null
                    live.datagrams()
                        // Only the transport's own failures, never the downstream's: only I/O failures are link
                        // states; anything else is a bug and must crash, not be shared. CancellationException is
                        // not an IOException, so it always goes through.
                        .catch { e -> failure = e as? IOException ?: throw e }
                        .collect { datagram ->
                            holdLimit?.cancel()
                            datagrams++
                            val firstDatagram = upSince ?: timeSource.markNow().also { upSince = it }
                            if (recovering && firstDatagram.elapsedNow() >= LINK_STABLE_AFTER) {
                                recovering = false
                                retryDelay = LINK_RETRY_FIRST
                            }
                            mutableStatus.value = if (recovering) {
                                LinkStatus.Recovering(datagrams)
                            } else {
                                LinkStatus.Running(datagrams)
                            }
                            parser.parseRaw(datagram.payload).forEach { raw ->
                                val message = (raw as? RawFrame.Valid)?.let { received(it.frame, datagram.sender) }
                                message?.let(HeartbeatMapper::vehicleStateOf)
                                    ?.let { activeVehicle.pinFirst(it.systemId) }
                                recorder.received(raw, message)
                                if (message != null) emit(message)
                            }
                        }
                    holdLimit?.cancel()
                    if (recovering && upSince != null) {
                        // A socket that worked for a while before failing, while recovering, leaves the link
                        // Recovering, so the HUD does not flicker with a flapping link; but only for
                        // LINK_STABLE_AFTER without datagrams, during the backoff or on a reopened socket that
                        // stays silent: past it, it is an outage. A flapping link delivers again well before.
                        val heldFailure = failure
                        holdLimit = launch {
                            delay(LINK_STABLE_AFTER)
                            mutableStatus.value = LinkStatus.Failed(heldFailure)
                        }
                    } else {
                        // A socket that delivered nothing is a real outage.
                        mutableStatus.value = failure.toStatus(reopening)
                    }
                    if (failure is BindException && !reopening) {
                        // The port belongs to another app until the operator acts: never retried. Stay "open"
                        // until the last subscriber leaves, so the failure stays in [status] meanwhile.
                        awaitCancellation()
                    }
                    // Stays Failed (or UnavailableRetrying) while waiting; Recovering with the first datagram,
                    // since a successful bind alone does not prove the link works.
                    reopening = true
                    recovering = true
                    delay(retryDelay)
                    retryDelay = minOf(retryDelay * 2, LINK_RETRY_MAX)
                }
            }
        }
            // The last subscriber left and the session was stopped: the next one starts clean, never
            // showing this one's count or failure.
            .onCompletion { cause ->
                recorder.sessionStopped()
                if (cause is CancellationException) mutableStatus.value = LinkStatus.Running(datagrams = 0)
            }
            .shareIn(scope = scope, started = SharingStarted.WhileSubscribed())
    }

    /** [flow], collected while keeping the session open, as a subscriber to [messages] does. */
    fun <T> whileOpen(flow: Flow<T>): Flow<T> = merge(messages.transform { }, flow)

    private fun IOException.toStatus(reopening: Boolean): LinkStatus = when {
        this is BindException && reopening -> LinkStatus.UnavailableRetrying
        this is BindException -> LinkStatus.Unavailable
        else -> LinkStatus.Failed(cause = this)
    }

    private fun received(frame: MavlinkFrame, sender: InetSocketAddress) = ReceivedMessage(
        systemId = frame.systemId,
        componentId = frame.componentId,
        message = MavlinkDecoder.decode(frame),
        sender = sender,
    )
}
