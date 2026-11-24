package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandRouteRepository
import com.owindev.gcs.core.domain.vehicle.command.RouteAcceptance
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.RouteStatus
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The vehicle's route on the flight screen (REQ-051): why commands are held, and the review of a route heard alone for
 * a whole link timeout, accepted with a slide (REQ-050). Only for the active vehicle heard live; every action is judged
 * again on fresh values, and the vehicle service judges the acceptance once more.
 */
@HiltViewModel
class HudRouteViewModel @Inject constructor(
    private val commands: CommandRepository,
    private val routes: CommandRouteRepository,
    private val vehicles: VehicleRepository,
    private val linkLoss: LinkLossRule,
) : ViewModel() {

    private val routeRule = RouteRule(linkLoss)
    private val request = MutableStateFlow<Request?>(null)

    /** A link lost by its age alone shows on no input, so the review is judged again as often as the command bar. */
    private val ticks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(RECHECK)
        }
    }

    val state: StateFlow<RouteUiState> =
        combine(commands.commandKey, routes.route, vehicles.vehicles, request, ticks) {
                key,
                route,
                vehicles,
                reviewing,
                _,
            ->
            val status = statusOf(key, route, vehicles)
            val sheet = reviewing?.toSheet(key, status)
            // A review that could not be shown once is over: it never comes back by itself when its route does.
            if (reviewing != null && sheet == null) request.compareAndSet(reviewing, null)
            RouteUiState(notice = status?.toNotice(), sheet = sheet)
        }
            // Nobody looks for a while: whatever was open was not confirmed, and never reopens by itself.
            .onCompletion { request.value = null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout = STOP_TIMEOUT), RouteUiState())

    /** Opens the review of the new route, only while it is the one heard alone; never for an alternating route. */
    fun onReviewRequested() {
        request.value = currentOffer()?.let(::Request) ?: return
    }

    fun onCancelled() {
        request.value = null
    }

    /**
     * Accepts the route the slide confirmed as [offer]. A slide on a key that moved meanwhile, for the same vehicle and
     * addresses, asks for another one; a route that changed closes the review, which never reopens by itself.
     */
    fun onConfirmed(offer: RouteOffer) {
        val reviewing = request.value ?: return
        val current = currentOffer()
        when {
            current == null || !current.sameRouteAs(offer) -> request.value = null

            offer != reviewing.offer || offer != current -> request.value = Request(current, slideAgain = true)

            else -> {
                request.value = null
                viewModelScope.launch {
                    if (routes.acceptRoute(offer) == RouteAcceptance.Outdated) {
                        currentOffer()?.takeIf { it.sameRouteAs(offer) }?.let {
                            request.value = Request(it, slideAgain = true)
                        }
                    }
                }
            }
        }
    }

    /** The route offered right now, judged on fresh values. */
    private fun currentOffer(): RouteOffer? {
        val key = commands.commandKey.value
        val changed = statusOf(key, routes.route.value, vehicles.vehicles.value) as? RouteStatus.Changed
        return if (key != null && changed != null) RouteOffer(key, changed.from, changed.to) else null
    }

    /** `null` with no active vehicle heard live on [key], as when the command bar is not shown. */
    private fun statusOf(key: CommandKey?, route: VehicleRoute?, vehicles: Map<SystemId, Vehicle>): RouteStatus? =
        key?.takeIf { vehicles[it.systemId]?.let(linkLoss::isLost) == false }?.let { routeRule.statusOn(it, route) }

    /** The review of [offer], shown only while its key is current and the route still changed as it says. */
    private data class Request(val offer: RouteOffer, val slideAgain: Boolean = false) {

        fun toSheet(key: CommandKey?, status: RouteStatus?): RouteSheet? =
            takeIf { key == offer.key && status == RouteStatus.Changed(offer.from, offer.to) }?.let {
                RouteSheet(systemId = offer.key.systemId.value, offer = offer, slideAgain = slideAgain)
            }
    }

    private companion object {
        val RECHECK: Duration = 250.milliseconds

        /** Survives a configuration change. */
        val STOP_TIMEOUT: Duration = 5.seconds

        fun RouteOffer.sameRouteAs(other: RouteOffer): Boolean =
            key.systemId == other.key.systemId && from == other.from && to == other.to
    }
}
