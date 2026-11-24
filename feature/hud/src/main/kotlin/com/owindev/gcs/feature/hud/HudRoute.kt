package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.RouteStatus
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute

/** What the flight screen shows of the vehicle's route (REQ-051): why commands are held, and the review of a new one. */
data class RouteUiState(val notice: RouteNotice? = null, val sheet: RouteSheet? = null)

/**
 * Why commands are held by the vehicle's route. The addresses stay [RouteAddress]es, hidden from `toString`, and are
 * shown only through [RouteAddress.display].
 */
sealed interface RouteNotice {

    /** Heard only from [to] for a whole link timeout; commands stay pinned to [from] until the operator accepts [to]. */
    data class Changed(val from: RouteAddress, val to: RouteAddress) : RouteNotice

    /** Heard from all of [addresses] within one link timeout, the newest first, commands pinned to [pinned]. */
    data class Alternating(val pinned: RouteAddress, val addresses: List<RouteAddress>) : RouteNotice
}

/** The review of [offer] for [systemId]; [slideAgain] after a slide on a key that had moved or found outdated. */
data class RouteSheet(val systemId: Int, val offer: RouteOffer, val slideAgain: Boolean = false)

/** [route]'s status, or `null` for none or one on another key: not known yet, so never a reason to send. */
internal fun RouteRule.statusOn(key: CommandKey, route: VehicleRoute?): RouteStatus? =
    route?.takeIf { it.key == key }?.let(::statusOf)

/** Every command held unless [status] is [RouteStatus.Pinned], whatever else allows it: a gate needs a positive sign. */
internal fun CommandBar.heldBy(status: RouteStatus?): CommandBar {
    val reason = when (status) {
        RouteStatus.Pinned -> return this
        is RouteStatus.Changed -> CommandUnavailable.RouteChanged
        is RouteStatus.Alternating -> CommandUnavailable.RouteAlternating
        null -> CommandUnavailable.RouteUnknown
    }
    return CommandBar(
        ground = ground.copy(unavailable = reason),
        returnToLaunch = returnToLaunch.copy(unavailable = reason),
        land = land.copy(unavailable = reason),
    )
}

internal fun RouteStatus.toNotice(): RouteNotice? = when (this) {
    RouteStatus.Pinned -> null
    is RouteStatus.Changed -> RouteNotice.Changed(from = from, to = to)
    is RouteStatus.Alternating -> RouteNotice.Alternating(pinned = pinned, addresses = addresses)
}
