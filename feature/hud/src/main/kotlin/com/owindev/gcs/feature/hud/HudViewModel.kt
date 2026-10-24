package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.time.DeviceClock
import com.owindev.gcs.core.domain.time.atLeastRecheck
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
import kotlinx.coroutines.awaitCancellation
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
 * The active vehicle's telemetry is judged by [staleness] on the same clock (REQ-026, REQ-029): the panel is
 * built again when something arrives, when a value goes stale and on every whole second of a stale value's age,
 * never at the telemetry's own rate of change.
 *
 * The active vehicle's alerts are shown in a banner while it is heard (REQ-028), the most urgent not yet
 * acknowledged; acknowledging one passes its occurrence to [AlertRepository], which decides what can be
 * acknowledged, but only the occurrence on the banner and only once it has been there for [ACKNOWLEDGE_GUARD],
 * timed on the [deviceClock], since a paused replay must not stop the operator's reading time. Link lost hides them,
 * as it outranks every alert (REQ-017).
 *
 * Nothing remembered is ever shown: when nobody observes the HUD for [STOP_TIMEOUT], the state goes back
 * to [HudUiState.Starting], so coming back never flashes an old CONNECTED before the fresh state.
 */
// One source per part of the HUD, the two rules that judge them and the clock; grouping them would only hide that.
@Suppress("LongParameterList")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HudViewModel @Inject constructor(
    linkRepository: LinkRepository,
    vehicleRepository: VehicleRepository,
    telemetryRepository: TelemetryRepository,
    private val alertRepository: AlertRepository,
    private val rule: LinkLossRule,
    private val staleness: StalenessRule,
    private val deviceClock: DeviceClock,
) : ViewModel() {

    /** The alert on the banner and since when; written as each state is built, read by [onAlertAcknowledged]. */
    private var shown: ShownAlert? = null

    /** Ticks only when the active vehicle's age needs re-reading; a new datagram does not restart it. */
    private val ticks: Flow<Unit> = combine(vehicleRepository.activeSystemId, vehicleRepository.vehicles) {
            activeSystemId,
            vehicles,
        ->
        activeSystemId?.let(vehicles::get)?.lastHeartbeat
    }
        .distinctUntilChanged()
        .flatMapLatest { lastHeartbeat -> if (lastHeartbeat == null) flowOf(Unit) else ticksFor(lastHeartbeat) }

    /** The active vehicle's telemetry, again whenever its panel changes with nothing new arriving. */
    private val telemetryTicks: Flow<Telemetry?> = telemetryRepository.activeTelemetry.flatMapLatest { telemetry ->
        if (telemetry == null) {
            flowOf(null)
        } else {
            flow {
                while (true) {
                    emit(telemetry)
                    delay(untilPanelChanges(telemetry, staleness)?.atLeastRecheck() ?: awaitCancellation())
                }
            }
        }
    }

    val state: StateFlow<HudUiState> = combine(
        linkRepository.traffic,
        vehicleRepository.activeSystemId,
        vehicleRepository.vehicles,
        ticks,
        telemetryTicks,
    ) { traffic, activeSystemId, vehicles, _, telemetry -> toUiState(traffic, activeSystemId, vehicles, telemetry) }
        .combine(alertRepository.activeAlerts) { state, alerts -> state.withAlerts(alerts) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeout = STOP_TIMEOUT,
                replayExpiration = Duration.ZERO,
            ),
            initialValue = HudUiState.Starting,
        )

    /**
     * Acknowledges the alert of [occurrence] the operator saw; an emergency is never acknowledged. Ignored unless it
     * is the one on the banner now and has been for [ACKNOWLEDGE_GUARD]: a tap meant for another, or for one shown
     * too briefly to be read, acknowledges nothing.
     */
    fun onAlertAcknowledged(occurrence: Long) {
        val current = shown ?: return
        if (current.occurrence == occurrence && current.since.elapsedNow() >= ACKNOWLEDGE_GUARD) {
            alertRepository.acknowledge(occurrence)
        }
    }

    private fun ticksFor(lastHeartbeat: TimeMark): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(untilNextCheck(lastHeartbeat.elapsedNow()).atLeastRecheck())
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
        telemetry: Telemetry?,
    ): HudUiState {
        // Only the link session's own vehicles: one from the session before is never shown in this one (REQ-037).
        val heard = vehicles.filterValues { it.origin == traffic.origin() }
        val active = activeSystemId?.let(heard::get)
        val otherVehicles = heard
            .filter { (systemId, vehicle) -> systemId != activeSystemId && !vehicle.isLost() }
            .keys
            .map(SystemId::value)
            .sorted()
        // Losing the active vehicle outranks everything else: it is the emergency (REQ-017). A local
        // fault is still shown on it, because it changes what the operator should look at.
        val sessionTelemetry = telemetry?.takeIf { it.origin == traffic.origin() }
        if (active != null && active.isLost()) {
            return HudUiState.LinkLost(
                lastKnown = active.toStatus(),
                sinceLastHeartbeat = active.lastHeartbeat.elapsedNow().inWholeSeconds.seconds,
                otherVehicles = otherVehicles,
                localFault = traffic.toLocalFault(),
                lastData = panelOf(sessionTelemetry, staleness, lostSince = active.lastHeartbeat),
                recorded = active.origin == DataOrigin.REPLAY,
            )
        }
        return when (traffic) {
            is LinkTraffic.Unavailable -> HudUiState.PortUnavailable(port = traffic.port)

            is LinkTraffic.UnavailableRetrying -> HudUiState.PortUnavailableRetrying(port = traffic.port)

            LinkTraffic.Failed -> HudUiState.LinkError

            LinkTraffic.ReplayFailed -> HudUiState.ReplayUnreadable

            is LinkTraffic.Receiving -> receiving(traffic.port, traffic.datagrams, active, otherVehicles)

            // Data is arriving, so the vehicle is shown as usual; the warning only says the link may fail again.
            is LinkTraffic.Recovering ->
                receiving(traffic.port, traffic.datagrams, active, otherVehicles, linkUnstable = true)

            // No port: the datagrams come from a recording, not from a socket.
            is LinkTraffic.Replaying -> receiving(port = null, traffic.datagrams, active, otherVehicles)
        }.withTelemetry(sessionTelemetry)
    }

    private fun receiving(
        port: Int?,
        datagrams: Long,
        active: Vehicle?,
        otherVehicles: List<Int>,
        linkUnstable: Boolean = false,
    ): HudUiState = if (active == null) {
        HudUiState.WaitingForVehicle(port = port, datagramCount = datagrams, linkUnstable = linkUnstable)
    } else {
        HudUiState.Connected(
            vehicle = active.toStatus(),
            port = port,
            datagramCount = datagrams,
            otherVehicles = otherVehicles,
            linkUnstable = linkUnstable,
        )
    }

    /** Only a heard vehicle has a telemetry panel: never while waiting or failing; a lost one keeps its last data. */
    private fun HudUiState.withTelemetry(telemetry: Telemetry?): HudUiState =
        if (this is HudUiState.Connected) copy(telemetry = panelOf(telemetry, staleness)) else this

    /** Only a heard vehicle has an alert banner: never while waiting, failing or after the link is lost. */
    private fun HudUiState.withAlerts(alerts: Alerts): HudUiState {
        val banner = (this as? HudUiState.Connected)?.let { alertBannerOf(alerts, it.telemetry) }
        val occurrence = (banner as? AlertBanner.Showing)?.occurrence
        if (occurrence != shown?.occurrence) shown = occurrence?.let { ShownAlert(it, deviceClock.markNow()) }
        return if (this is HudUiState.Connected && banner != null) copy(alert = banner) else this
    }

    private fun Vehicle.isLost(): Boolean = rule.isLost(this)

    private companion object {
        /** Survives a configuration change without closing the link. */
        val STOP_TIMEOUT = 5.seconds

        val TICK: Duration = 1.seconds
    }
}

/** The alert of [occurrence] on the banner, there [since]. */
private data class ShownAlert(val occurrence: Long, val since: TimeMark)

private fun Vehicle.toStatus() = VehicleStatus(
    systemId = state.systemId.value,
    flightMode = state.flightMode,
    armed = state.armed,
)

/** Where this traffic comes from: a replay's or the live link's. */
private fun LinkTraffic.origin(): DataOrigin = when (this) {
    is LinkTraffic.Replaying, LinkTraffic.ReplayFailed -> DataOrigin.REPLAY
    else -> DataOrigin.LIVE
}

private fun LinkTraffic.toLocalFault(): LocalLinkFault? = when (this) {
    is LinkTraffic.Receiving, is LinkTraffic.Replaying, LinkTraffic.ReplayFailed -> null
    is LinkTraffic.Recovering -> LocalLinkFault.Unstable
    is LinkTraffic.Unavailable -> LocalLinkFault.PortUnavailable(port = port)
    is LinkTraffic.UnavailableRetrying -> LocalLinkFault.PortUnavailableRetrying(port = port)
    LinkTraffic.Failed -> LocalLinkFault.Failed
}
