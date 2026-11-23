package com.owindev.gcs.core.domain.vehicle.command

import kotlin.time.ComparableTimeMark

/** A source address the vehicle was heard from: shown to the operator as [display], never logged (MASVS-PRIVACY). */
data class RouteAddress(val host: String, val port: Int) {

    /** `host:port`, with an IPv6 host in brackets. */
    val display: String
        get() = if (IPV6_SEPARATOR in host) "[$host]:$port" else "$host:$port"

    override fun toString(): String = "RouteAddress(hidden)"

    private companion object {
        const val IPV6_SEPARATOR = ':'
    }
}

/**
 * The active vehicle's route for commands in its session (REQ-051): the [pinned] address commands go to, and when each
 * address was last [heard], for the connection of [key].
 */
data class VehicleRoute(
    val key: CommandKey,
    val pinned: RouteAddress,
    val heard: Map<RouteAddress, ComparableTimeMark>,
)

/** What [RouteRule] makes of a [VehicleRoute]. */
sealed interface RouteStatus {

    /** Only the pinned address is heard: commands may go to it. */
    data object Pinned : RouteStatus

    /** Only [to] has been heard for a whole link timeout: the operator may accept it in place of [from]. */
    data class Changed(val from: RouteAddress, val to: RouteAddress) : RouteStatus

    /** Several [addresses] were heard within one link timeout, the newest first: nothing can be accepted. */
    data class Alternating(val pinned: RouteAddress, val addresses: List<RouteAddress>) : RouteStatus
}

/** The operator's acceptance of [to] in place of [from], confirmed on [key] (REQ-051). */
data class RouteOffer(val key: CommandKey, val from: RouteAddress, val to: RouteAddress)

sealed interface RouteAcceptance {

    /** Commands go to the new route from now on, under a new key. */
    data object Accepted : RouteAcceptance

    /** The key moved or the route is no longer the one offered: nothing changed. */
    data object Outdated : RouteAcceptance
}
