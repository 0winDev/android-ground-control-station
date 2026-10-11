package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Maps the link traffic and the active vehicle to what the HUD shows.
 *
 * The active vehicle is chosen by [VehicleRepository.activeSystemId] (REQ-020), never here. Whether its
 * link is lost is decided by [LinkLossRule]: the watchdog's [VehicleLink.LOST], or the rule applied to the
 * vehicle's age right now, because the watchdog's timer can run late after the device sleeps (#12). A
 * ticker re-reads the age on every whole second and exactly at the timeout, so a link that times out
 * shows LOST on time and the time since the last HEARTBEAT keeps counting.
 *
 * Nothing remembered is ever shown: when nobody observes the HUD for [STOP_TIMEOUT], the state goes back
 * to [HudUiState.Starting], so coming back never flashes an old CONNECTED before the fresh state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HudViewModel @Inject constructor(
    linkRepository: LinkRepository,
    vehicleRepository: VehicleRepository,
    private val rule: LinkLossRule,
) : ViewModel() {

    /** Ticks only when the active vehicle's age needs re-reading; a new datagram does not restart it. */
    private val ticks: Flow<Unit> = combine(vehicleRepository.activeSystemId, vehicleRepository.vehicles) {
            activeSystemId,
            vehicles,
        ->
        activeSystemId?.let(vehicles::get)?.lastHeartbeat
    }
        .distinctUntilChanged()
        .flatMapLatest { lastHeartbeat -> if (lastHeartbeat == null) flowOf(Unit) else ticksFor(lastHeartbeat) }

    val state: StateFlow<HudUiState> = combine(
        linkRepository.traffic,
        vehicleRepository.activeSystemId,
        vehicleRepository.vehicles,
        ticks,
    ) { traffic, activeSystemId, vehicles, _ -> toUiState(traffic, activeSystemId, vehicles) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeout = STOP_TIMEOUT,
                replayExpiration = Duration.ZERO,
            ),
            initialValue = HudUiState.Starting,
        )

    private fun ticksFor(lastHeartbeat: TimeMark): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(untilNextCheck(lastHeartbeat.elapsedNow()))
        }
    }

    /** To the next whole second of the age, so the count never skips one, or to the timeout if sooner. */
    private fun untilNextCheck(age: Duration): Duration {
        val toNextSecond = TICK - (age.inWholeNanoseconds % TICK.inWholeNanoseconds).nanoseconds
        val toTimeout = rule.timeout - age
        return if (toTimeout > Duration.ZERO && toTimeout < toNextSecond) toTimeout else toNextSecond
    }

    private fun toUiState(
        traffic: LinkTraffic,
        activeSystemId: SystemId?,
        vehicles: Map<SystemId, Vehicle>,
    ): HudUiState {
        val active = activeSystemId?.let(vehicles::get)
        val otherVehicles = vehicles
            .filter { (systemId, vehicle) -> systemId != activeSystemId && !vehicle.isLost() }
            .keys
            .map(SystemId::value)
            .sorted()
        // Losing the active vehicle outranks everything else: it is the emergency (REQ-017). A local
        // fault is still shown on it, because it changes what the operator should look at.
        if (active != null && active.isLost()) {
            return HudUiState.LinkLost(
                lastKnown = active.toStatus(),
                sinceLastHeartbeat = active.lastHeartbeat.elapsedNow().inWholeSeconds.seconds,
                otherVehicles = otherVehicles,
                localFault = traffic.toLocalFault(),
            )
        }
        return when (traffic) {
            is LinkTraffic.Unavailable -> HudUiState.PortUnavailable(port = traffic.port)

            LinkTraffic.Failed -> HudUiState.LinkError

            is LinkTraffic.Receiving -> if (active == null) {
                HudUiState.WaitingForVehicle(port = traffic.port, datagramCount = traffic.datagrams)
            } else {
                HudUiState.Connected(
                    vehicle = active.toStatus(),
                    port = traffic.port,
                    datagramCount = traffic.datagrams,
                    otherVehicles = otherVehicles,
                )
            }
        }
    }

    private fun Vehicle.isLost(): Boolean =
        link == VehicleLink.LOST || rule.linkAfter(lastHeartbeat.elapsedNow()) == VehicleLink.LOST

    private companion object {
        /** Survives a configuration change without closing the link. */
        val STOP_TIMEOUT = 5.seconds

        val TICK: Duration = 1.seconds
    }
}

private fun Vehicle.toStatus() = VehicleStatus(
    systemId = state.systemId.value,
    flightMode = state.flightMode,
    armed = state.armed,
)

private fun LinkTraffic.toLocalFault(): LocalLinkFault? = when (this) {
    is LinkTraffic.Receiving -> null
    is LinkTraffic.Unavailable -> LocalLinkFault.PortUnavailable(port = port)
    LinkTraffic.Failed -> LocalLinkFault.Failed
}
