package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRepository
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects what the senders observe of the uplink (REQ-045): [GcsHeartbeatSender] reports its periods and resets it,
 * [com.owindev.gcs.data.vehicle.command.CommandExchange] reports commands without answer and answers, and
 * [com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester] the answers to its stream requests.
 *
 * An answer counts only from the active vehicle's command route at the moment it is counted (REQ-051): one from a route
 * the operator replaced, or before any is pinned, proves nothing about the route commands now take.
 */
@Singleton
internal class DefaultUplinkRepository @Inject constructor(private val timeSource: TimeSource.WithComparableMarks) :
    UplinkRepository {

    private val mutableHealth = MutableStateFlow(UplinkHealth.HEALTHY)

    /** Held while an answer is checked and counted, so none from a replaced route counts after the restart. */
    private val lock = Any()

    private var answersFrom: InetSocketAddress? = null

    override val health: StateFlow<UplinkHealth> = mutableHealth.asStateFlow()

    fun heartbeatSent() = mutableHealth.update { it.afterHeartbeatSent() }

    fun heartbeatFailed() = mutableHealth.update { it.afterHeartbeatFailed() }

    /** Counted only when [to] is the active vehicle's command route: a command by a replaced route proves nothing. */
    fun commandUnanswered(to: InetSocketAddress) = synchronized(lock) {
        if (to == answersFrom) mutableHealth.update { it.afterCommandUnanswered(timeSource.markNow()) }
    }

    /** Only for a COMMAND_ACK from the active vehicle's system ID; counted only when [from] is its command route. */
    fun commandAnswered(from: InetSocketAddress) = synchronized(lock) {
        if (from == answersFrom) mutableHealth.update { it.afterCommandAnswered() }
    }

    /** The route the active vehicle's first HEARTBEAT pinned: what was observed so far still holds. */
    fun answersFrom(route: InetSocketAddress) = synchronized(lock) {
        answersFrom = route
    }

    /** A new command route, or none at the end of a session: nothing observed before counts. */
    fun restartOn(route: InetSocketAddress?) = synchronized(lock) {
        answersFrom = route
        mutableHealth.value = UplinkHealth.HEALTHY
    }

    /** Whatever was observed belonged to a vehicle no longer CONNECTED, or to a session that ended. */
    fun reset() = synchronized(lock) {
        mutableHealth.value = UplinkHealth.HEALTHY
    }
}
