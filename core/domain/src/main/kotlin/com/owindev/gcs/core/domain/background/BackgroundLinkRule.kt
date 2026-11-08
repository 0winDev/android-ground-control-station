package com.owindev.gcs.core.domain.background

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink

/**
 * When the link is kept running in the background (REQ-046, ADR 0009). While any live vehicle's last known state is
 * armed, heard or not, it must run and nothing the operator does in the app stops it: as for a replay, every vehicle
 * behind the active one's address hears this GCS's HEARTBEAT.
 */
class BackgroundLinkRule(private val linkLoss: LinkLossRule, private val recording: RecordingRule) {

    /**
     * Always with a live vehicle armed, so arming overrides the operator's Disconnect; otherwise while the [active]
     * live vehicle is connected, by the watchdog's verdict or by [linkLoss] on its age now, and was not disconnected.
     */
    fun mustRun(vehicles: Collection<Vehicle>, active: Vehicle?, disconnectedByUser: Boolean): Boolean =
        anyLiveArmed(vehicles) || (active != null && active.isLive && active.isConnected && !disconnectedByUser)

    /** Whether Disconnect or removing the app from recents may stop it, and so whether Disconnect is offered. */
    fun operatorMayStop(vehicles: Collection<Vehicle>): Boolean = !anyLiveArmed(vehicles)

    /** Ends with no live vehicle, or once a disarmed one is silent for the recording's limit (REQ-036). */
    fun endsFor(vehicles: Collection<Vehicle>, active: Vehicle?): Boolean = when {
        anyLiveArmed(vehicles) -> false
        active == null || !active.isLive -> true
        else -> recording.closesAfter(active.lastHeartbeat.elapsedNow())
    }

    private fun anyLiveArmed(vehicles: Collection<Vehicle>): Boolean = vehicles.any { it.isLive && it.state.armed }

    private val Vehicle.isLive: Boolean
        get() = origin == DataOrigin.LIVE

    private val Vehicle.isConnected: Boolean
        get() = link == VehicleLink.CONNECTED || linkLoss.linkAfter(lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED
}
