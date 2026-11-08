package com.owindev.gcs.link

import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.transformWhile

/** The active vehicle as the background link's notification shows it (REQ-046). */
internal data class LinkNotice(val state: VehicleState, val connected: Boolean, val disconnectOffered: Boolean)

/** What the operator did to the background link from outside the app's screens. */
internal enum class LinkRequest { DISCONNECT, TASK_REMOVED, NOTIFICATION_DISMISSED }

internal enum class LinkAction { STOP, REFUSE, REPOST }

/**
 * Applies [BackgroundLinkRule] for [VehicleLinkService], so its decisions are tested without Android: what the
 * notification shows, when the service ends, what each request of the operator does, and the wake lock (REQ-046).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LinkServiceController(
    private val vehicles: VehicleRepository,
    private val rule: BackgroundLinkRule,
    private val wakeHold: WakeHold,
    private val state: BackgroundLinkState,
) {

    /**
     * Collecting it keeps the link observed, so its session, watchdog, HEARTBEAT and recording run; it completes when
     * the rule ends the service, checked again every [RECHECK] since a silent vehicle changes nothing.
     */
    val notices: Flow<LinkNotice> = combine(vehicles.activeSystemId, vehicles.vehicles) { systemId, all ->
        all.values to systemId?.let(all::get)
    }
        .transformLatest { active ->
            while (true) {
                emit(active)
                delay(RECHECK)
            }
        }
        .transformWhile { (all, active) ->
            if (!rule.operatorMayStop(all)) state.disconnectedByUser.value = false
            val goesOn = active != null && !rule.endsFor(all, active)
            if (goesOn) emit(noticeOf(all, checkNotNull(active)))
            goesOn
        }
        .distinctUntilChanged()

    fun started() {
        state.running.value = true
        wakeHold.acquire()
    }

    fun stopped() {
        state.running.value = false
        wakeHold.release()
    }

    /** Dismissing the notification never stops the service; the others stop it only while the rule allows. */
    fun actionFor(request: LinkRequest): LinkAction = when (request) {
        LinkRequest.NOTIFICATION_DISMISSED -> LinkAction.REPOST

        LinkRequest.DISCONNECT, LinkRequest.TASK_REMOVED -> when {
            !rule.operatorMayStop(vehicles.vehicles.value.values) -> LinkAction.REFUSE
            else -> LinkAction.STOP.also { state.disconnectedByUser.value = request == LinkRequest.DISCONNECT }
        }
    }

    private fun noticeOf(all: Collection<Vehicle>, active: Vehicle) = LinkNotice(
        state = active.state,
        connected = active.link == VehicleLink.CONNECTED,
        disconnectOffered = rule.operatorMayStop(all),
    )
}

private val RECHECK: Duration = 1.seconds
