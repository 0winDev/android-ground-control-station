package com.owindev.gcs.core.domain.vehicle.command

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import kotlin.time.ComparableTimeMark

/**
 * Judges the active vehicle's route (REQ-051) on the addresses heard within one link timeout before its newest
 * HEARTBEAT. The window follows the HEARTBEATs, not the clock: the status never changes while nothing is heard, and
 * the link-loss rule says what silence means.
 */
class RouteRule(private val linkLoss: LinkLossRule) {

    fun statusOf(route: VehicleRoute): RouteStatus {
        val recent = recentOf(route.heard)
        return when {
            recent.size > 1 -> RouteStatus.Alternating(route.pinned, recent)
            recent.isEmpty() || recent.single() == route.pinned -> RouteStatus.Pinned
            else -> RouteStatus.Changed(from = route.pinned, to = recent.single())
        }
    }

    /** The addresses heard within one link timeout before the newest of [heard], the newest first. */
    fun recentOf(heard: Map<RouteAddress, ComparableTimeMark>): List<RouteAddress> {
        val newest = heard.values.maxOrNull() ?: return emptyList()
        return heard.entries
            .filter { (_, at) -> linkLoss.linkAfter(newest - at) == VehicleLink.CONNECTED }
            .sortedByDescending { (_, at) -> at }
            .map { (address, _) -> address }
    }
}
