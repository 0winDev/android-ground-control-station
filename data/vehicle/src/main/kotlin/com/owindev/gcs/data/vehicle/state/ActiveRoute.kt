package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.RouteStatus
import java.net.InetSocketAddress
import kotlin.time.ComparableTimeMark

/**
 * The active live vehicle's command route in its session (REQ-043, REQ-051): the address of its first HEARTBEAT, and
 * when each address it was heard from was last heard. Only the watchdog writes it; any thread reads it.
 */
internal class ActiveRoute(linkLoss: LinkLossRule) {

    private val rule = RouteRule(linkLoss)

    /** One value, so a reader never mixes the address of one pin with the addresses heard for another. */
    @Volatile
    private var pin: Pin? = null

    /** Keeps only the pinned address and those [RouteRule] still counts, so made-up addresses never pile up. */
    fun heard(sender: InetSocketAddress, at: ComparableTimeMark) {
        val current = pin ?: Pin(sender, emptyMap())
        val all = current.heard + (sender.toRoute() to at)
        val kept = rule.recentOf(all).toSet() + current.address.toRoute()
        pin = current.copy(heard = all.filterKeys { it in kept })
    }

    fun reset() {
        pin = null
    }

    /** Where a command may go now: nowhere before the first HEARTBEAT, nor while the route is not pinned. */
    fun check(): RouteCheck {
        val current = pin ?: return RouteCheck.None
        return if (rule.statusOf(current.address.toRoute(), current.heard) == RouteStatus.Pinned) {
            RouteCheck.Open(current.address)
        } else {
            RouteCheck.Held
        }
    }

    private data class Pin(val address: InetSocketAddress, val heard: Map<RouteAddress, ComparableTimeMark>)
}

internal sealed interface RouteCheck {

    data object None : RouteCheck

    data object Held : RouteCheck

    data class Open(val address: InetSocketAddress) : RouteCheck
}

/** The literal address, never a reverse DNS lookup ([InetSocketAddress.getHostName] would make one). */
internal fun InetSocketAddress.toRoute() = RouteAddress(hostString, port)
