package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.RouteAcceptance
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.RouteStatus
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import java.net.InetSocketAddress
import kotlin.time.ComparableTimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The active live vehicle's command route in its session (REQ-043, REQ-051): the address of its first HEARTBEAT, or the
 * one the operator accepted, and when each address it was heard from was last heard. The [uplink] counts answers from
 * that address only. Only the watchdog writes it; any thread reads it.
 */
internal class ActiveRoute(
    linkLoss: LinkLossRule,
    private val activeVehicle: ActiveVehicle,
    private val activeLinkChanges: ActiveLinkChanges,
    private val uplink: DefaultUplinkRepository,
) {

    private val rule = RouteRule(linkLoss)

    /** One value, so a reader never mixes the address of one pin with the addresses heard for another. */
    private val mutablePin = MutableStateFlow<RoutePin?>(null)

    val pin: StateFlow<RoutePin?> = mutablePin.asStateFlow()

    /**
     * Records a HEARTBEAT of [systemId] if it is the active live vehicle's, keeping only the pinned address and the
     * [OTHERS_KEPT] newest others: all [RouteRule] needs, so a flood of made-up addresses costs nothing more.
     */
    fun heard(systemId: SystemId, origin: DataOrigin, sender: InetSocketAddress, at: ComparableTimeMark) {
        if (systemId != activeVehicle.systemId.value || origin != DataOrigin.LIVE) return
        val before = mutablePin.value
        val current = before ?: RoutePin(sender, emptyMap())
        val pinned = current.address.toRoute()
        val all = current.heard + (sender.toRoute() to at)
        val others = (all - pinned).entries.sortedByDescending { (_, heardAt) -> heardAt }.take(OTHERS_KEPT)
        mutablePin.value = current.copy(heard = all.filterKeys { it == pinned } + others.associate { it.toPair() })
        if (before == null) uplink.answersFrom(sender)
    }

    /**
     * Pins [offer]'s new route if it is offered on the [current] key and the route is still changed from and to the
     * offer's addresses; then [lastSender], the address of its newest HEARTBEAT, is the new one. A LOST moves the key,
     * and a route accepted while the vehicle is silent still sends nothing until it is heard ([check] is not enough on
     * its own: the vehicle repository checks the link first).
     */
    fun accept(offer: RouteOffer, current: CommandKey?, lastSender: InetSocketAddress?): RouteAcceptance {
        val offered = RouteStatus.Changed(offer.from, offer.to)
        val acceptable = offer.key == current && mutablePin.value?.let(::statusOf) == offered
        if (lastSender == null || !acceptable) return RouteAcceptance.Outdated
        // The key moves before the pin, so no check under the sender's lock pairs the old key with the new route; the
        // telemetry and parameter requests start again there.
        activeLinkChanges.announce(VehicleTarget(offer.key.systemId, lastSender))
        mutablePin.value = mutablePin.value?.copy(address = lastSender)
        uplink.restartOn(lastSender)
        return RouteAcceptance.Accepted
    }

    /** The session ended: the next one pins its own first address, and nothing observed before counts. */
    fun reset() {
        mutablePin.value = null
        uplink.restartOn(null)
    }

    /** Where a command may go now: nowhere before the first HEARTBEAT, nor while the route is not pinned. */
    fun check(): RouteCheck {
        val current = mutablePin.value ?: return RouteCheck.None
        return if (statusOf(current) == RouteStatus.Pinned) RouteCheck.Open(current.address) else RouteCheck.Held
    }

    private fun statusOf(pin: RoutePin): RouteStatus = rule.statusOf(pin.address.toRoute(), pin.heard)

    private companion object {
        /** Two others heard within a timeout already make the route alternate. */
        const val OTHERS_KEPT = 2
    }
}

/** Where commands go, and when each address the active vehicle was heard from was last heard. */
internal data class RoutePin(val address: InetSocketAddress, val heard: Map<RouteAddress, ComparableTimeMark>) {

    fun toVehicleRoute(key: CommandKey) = VehicleRoute(key, address.toRoute(), heard)
}

internal sealed interface RouteCheck {

    data object None : RouteCheck

    data object Held : RouteCheck

    data class Open(val address: InetSocketAddress) : RouteCheck
}

/** The literal address, never a reverse DNS lookup ([InetSocketAddress.getHostName] would make one). */
internal fun InetSocketAddress.toRoute() = RouteAddress(hostString, port)
