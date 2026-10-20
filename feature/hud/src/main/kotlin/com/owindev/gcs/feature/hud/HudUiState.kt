package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration

/** What the HUD shows about the vehicle link and the active vehicle. */
sealed interface HudUiState {

    /** Nothing fresh to show yet; never a remembered state, which could be out of date. */
    data object Starting : HudUiState

    /**
     * Listening on [port], or playing a recording when it is `null` (REQ-037), but no vehicle has been heard yet.
     * [linkUnstable] while the link is receiving again after a failure but has not stayed up long enough to call it
     * recovered.
     */
    data class WaitingForVehicle(val port: Int?, val datagramCount: Long, val linkUnstable: Boolean = false) :
        HudUiState

    /**
     * The active [vehicle] is being heard; [otherVehicles] are other system IDs heard right now. Its mode
     * and armed state carry no age because this state only exists while it is younger than the link-loss
     * timeout: past it, the state is [LinkLost]. [linkUnstable] as in [WaitingForVehicle]: the vehicle is
     * still decided by its HEARTBEATs, the warning only says the link on this device may fail again.
     * [telemetry] is the vehicle's, each part with its staleness (REQ-029). [alert] is its alert banner (REQ-028),
     * only while it is heard: [LinkLost] outranks every alert (REQ-017). [port] as in [WaitingForVehicle].
     */
    data class Connected(
        val vehicle: VehicleStatus,
        val port: Int?,
        val datagramCount: Long,
        val otherVehicles: List<Int>,
        val linkUnstable: Boolean = false,
        val telemetry: TelemetryPanel = TelemetryPanel(),
        val alert: AlertBanner = AlertBanner.Hidden,
    ) : HudUiState

    /**
     * The active vehicle is no longer heard (REQ-017): [lastKnown] is its state at its last HEARTBEAT,
     * [sinceLastHeartbeat] how long ago that was, in whole seconds. [localFault] is set when the problem is
     * on this device, not the vehicle.
     */
    data class LinkLost(
        val lastKnown: VehicleStatus,
        val sinceLastHeartbeat: Duration,
        val otherVehicles: List<Int>,
        val localFault: LocalLinkFault? = null,
    ) : HudUiState

    data class PortUnavailable(val port: Int) : HudUiState

    /** [port] was taken while reopening the link; unlike [PortUnavailable], the link keeps retrying. */
    data class PortUnavailableRetrying(val port: Int) : HudUiState

    data object LinkError : HudUiState
}

/** What the HUD shows of one vehicle (REQ-018). */
data class VehicleStatus(val systemId: Int, val flightMode: FlightMode, val armed: Boolean)

/** A failure of this device's side of the link, which no HEARTBEAT from the vehicle can fix. */
sealed interface LocalLinkFault {

    data class PortUnavailable(val port: Int) : LocalLinkFault

    data class PortUnavailableRetrying(val port: Int) : LocalLinkFault

    data object Failed : LocalLinkFault

    /** The link failed and is receiving again, but has not stayed up long enough to call it recovered. */
    data object Unstable : LocalLinkFault
}
