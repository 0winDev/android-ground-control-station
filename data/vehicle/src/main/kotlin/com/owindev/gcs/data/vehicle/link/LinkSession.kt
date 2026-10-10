package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkFrame
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import java.io.IOException
import java.net.BindException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
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

/**
 * How long the repositories keep observing the session after their last observer leaves: keeps the link
 * open across short gaps, such as a configuration change.
 */
internal val LINK_STOP_TIMEOUT: Duration = 5.seconds

/**
 * The one place that receives, parses and decodes the vehicle link; every repository builds on it.
 *
 * The transport can only be collected once (a second collection would bind the port twice and fail),
 * so it is shared in the application scope. The socket stays open while anyone collects [messages] or
 * [whileOpen] (each repository already waits [LINK_STOP_TIMEOUT] before letting go); after that it
 * closes and [status] goes back to `Running(0)`; the next observer starts a new session with a new
 * socket and a new parser (it keeps partial frames between datagrams).
 *
 * [messages] has no replay: a late subscriber only gets messages that arrive after it subscribed, so an
 * old HEARTBEAT is never delivered as a new one. What a late subscriber needs to know, such as a bind
 * failure, is in [status].
 */
@Singleton
internal class LinkSession @Inject constructor(
    private val transport: UdpTransport,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val mutableStatus = MutableStateFlow<LinkStatus>(LinkStatus.Running(datagrams = 0))

    /** The local UDP port the link listens on. */
    val port: Int get() = transport.port

    /** The state of the current session; it only changes while the session is open. */
    val status: StateFlow<LinkStatus> = mutableStatus.asStateFlow()

    // Built on first use, so the transport is only asked for its flow once someone observes the link.
    val messages: SharedFlow<ReceivedMessage> by lazy {
        flow {
            val parser = MavlinkParser(MavlinkMessages)
            var datagrams = 0L
            transport.datagrams().collect { datagram ->
                datagrams++
                mutableStatus.value = LinkStatus.Running(datagrams)
                parser.parse(datagram).forEach { frame -> emit(received(frame)) }
            }
        }
            .catch { failure ->
                // Only I/O failures are link states; anything else is a bug and must crash, not be shared.
                mutableStatus.value = (failure as? IOException ?: throw failure).toStatus()
                // Stay "open" until the last subscriber leaves, so the failure stays in [status] meanwhile.
                awaitCancellation()
            }
            // The last subscriber left and the session was stopped: the next one starts clean, never
            // showing this one's count or failure.
            .onCompletion { cause ->
                if (cause is CancellationException) mutableStatus.value = LinkStatus.Running(datagrams = 0)
            }
            .shareIn(scope = scope, started = SharingStarted.WhileSubscribed())
    }

    /** [flow], collected while keeping the session open, as a subscriber to [messages] does. */
    fun <T> whileOpen(flow: Flow<T>): Flow<T> = merge(messages.transform { }, flow)

    private fun IOException.toStatus(): LinkStatus = when (this) {
        is BindException -> LinkStatus.Unavailable
        else -> LinkStatus.Failed(cause = this)
    }

    private fun received(frame: MavlinkFrame) = ReceivedMessage(
        systemId = frame.systemId,
        componentId = frame.componentId,
        message = MavlinkDecoder.decode(frame),
    )
}
