package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.mavlink.RawFrame
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.HeartbeatMapper
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
 *
 * Instead of the live link it can play a recording ([replay], REQ-037), through the same parser and decoder, until
 * [live] gives the link back. The two never run together: the live session, its socket and its recording are closed
 * before the replay's first datagram. A replay is passive: it has no socket, never retries, never feeds the recorder,
 * and pins the replay's own active vehicle. Each switch starts a new [session], set before the source changes, so
 * anything that must not reach the vehicle can check it first, and every message carries the session it belongs to.
 * The pipeline's time switches inside each source, once the one before has finished: the [pipelineClock] follows the
 * replay's clock from its first datagram and the device again from the live link's, so a live datagram is never marked
 * on a replay's time. A replay that ends or fails stays in [status] until [live] or until nobody observes it; observed
 * again, it plays its source from the start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class LinkSession @Inject constructor(
    private val live: DatagramSource,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks,
    private val pipelineClock: PipelineClock,
    private val recorder: FlightRecorder,
    private val activeVehicle: ActiveVehicle,
) {

    private val mutableStatus = MutableStateFlow<LinkStatus>(LinkStatus.Running(datagrams = 0))
    private val epochs = AtomicLong(FIRST_SESSION.epoch)
    private val mutableSession = MutableStateFlow(FIRST_SESSION)
    private val source = MutableStateFlow<Source>(Source.Live(FIRST_SESSION))

    /** The session the link is in, live or a replay; it changes before the messages do. */
    val session: StateFlow<SessionId> = mutableSession.asStateFlow()

    /**
     * The state of the current session; it only changes while the session is open. The datagram count runs
     * for the whole session, across reopenings of the socket.
     */
    val status: StateFlow<LinkStatus> = mutableStatus.asStateFlow()

    // Built on first use, so a source is only asked for its flow once someone observes the link.
    val messages: SharedFlow<ReceivedMessage> by lazy {
        // Switching cancels the current source and waits for it to finish before starting the next.
        source.flatMapLatest { current ->
            when (current) {
                is Source.Live -> liveMessages(current.session)
                is Source.Replay -> replayMessages(current)
            }
        }
            // The last subscriber left and the session was stopped: the next one starts clean, never
            // showing this one's count or failure.
            .onCompletion { cause ->
                if (cause is CancellationException) mutableStatus.value = startingStatus(source.value)
            }
            .shareIn(scope = scope, started = SharingStarted.WhileSubscribed())
    }

    /** Plays [datagrams] on [clock] instead of the live link or the replay before, closing it first. */
    fun replay(datagrams: DatagramSource, clock: ReplayClock) {
        val next = SessionId(DataOrigin.REPLAY, epochs.incrementAndGet())
        activeVehicle.startReplay(next)
        mutableSession.value = next
        mutableStatus.value = LinkStatus.Replaying(datagrams = 0)
        source.value = Source.Replay(datagrams, clock, next)
    }

    /** Gives the link back to a new live session, ending any replay first. */
    fun live() {
        val next = SessionId(DataOrigin.LIVE, epochs.incrementAndGet())
        source.value = Source.Live(next)
        mutableStatus.value = LinkStatus.Running(datagrams = 0)
        activeVehicle.endReplay()
        mutableSession.value = next
    }

    /** [flow], collected while keeping the session open, as a subscriber to [messages] does. */
    fun <T> whileOpen(flow: Flow<T>): Flow<T> = merge(messages.transform { }, flow)

    private fun liveMessages(session: SessionId): Flow<ReceivedMessage> = flow {
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
                            val message = decoded(raw, datagram.sender, session)
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
        .onStart {
            pipelineClock.endReplay()
            mutableStatus.value = LinkStatus.Running(datagrams = 0)
        }
        // Stopped by the last subscriber leaving or by a replay: either way the recording closes.
        .onCompletion { recorder.sessionStopped() }

    private fun replayMessages(replay: Source.Replay): Flow<ReceivedMessage> = flow {
        // One parser for the whole recording: a replay is never reopened.
        val parser = MavlinkParser(MavlinkMessages)
        var played = 0L
        var failure: IOException? = null
        pipelineClock.startReplay(replay.clock)
        mutableStatus.value = LinkStatus.Replaying(played)
        replay.datagrams.datagrams()
            .catch { e -> failure = e as? IOException ?: throw e }
            .collect { datagram ->
                played++
                mutableStatus.value = LinkStatus.Replaying(played)
                parser.parseRaw(datagram.payload).forEach { raw ->
                    decoded(raw, datagram.sender, replay.session)?.let { emit(it) }
                }
            }
        mutableStatus.value = failure?.let(LinkStatus::ReplayFailed) ?: LinkStatus.ReplayEnded(played)
        // Open until the replay is stopped or nobody observes it, so its end stays in [status] meanwhile.
        awaitCancellation()
    }

    private fun startingStatus(current: Source): LinkStatus = when (current) {
        is Source.Live -> LinkStatus.Running(datagrams = 0)
        is Source.Replay -> LinkStatus.Replaying(datagrams = 0)
    }

    private fun IOException.toStatus(reopening: Boolean): LinkStatus = when {
        this is BindException && reopening -> LinkStatus.UnavailableRetrying
        this is BindException -> LinkStatus.Unavailable
        else -> LinkStatus.Failed(cause = this)
    }

    /** [raw] decoded if it is a valid frame; an autopilot HEARTBEAT pins [session]'s active vehicle first. */
    private fun decoded(raw: RawFrame, sender: InetSocketAddress, session: SessionId): ReceivedMessage? {
        val message = (raw as? RawFrame.Valid)?.let { received(it.frame, sender, session) }
        message?.let(HeartbeatMapper::vehicleStateOf)?.let { activeVehicle.pinFirst(it.systemId, session) }
        return message
    }

    private fun received(frame: MavlinkFrame, sender: InetSocketAddress, session: SessionId) = ReceivedMessage(
        systemId = frame.systemId,
        componentId = frame.componentId,
        message = MavlinkDecoder.decode(frame),
        sender = sender,
        session = session,
    )

    /** Where the datagrams of a [session] come from. */
    private sealed interface Source {
        val session: SessionId

        data class Live(override val session: SessionId) : Source

        data class Replay(val datagrams: DatagramSource, val clock: ReplayClock, override val session: SessionId) :
            Source
    }
}
