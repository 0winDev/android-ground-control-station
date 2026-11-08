package com.owindev.gcs.core.domain.background

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink

/**
 * When the link is kept running in the background for the active vehicle (REQ-046, ADR 0009). While the vehicle's last
 * known state is armed, heard or not, nothing the operator does in the app stops it, and it never ends by itself.
 */
class BackgroundLinkRule(private val linkLoss: LinkLossRule, private val recording: RecordingRule) {

    /** Only for a live vehicle connected by the watchdog's verdict or by [linkLoss] on its age now. */
    fun startsFor(active: Vehicle?): Boolean = active != null && active.isLive && active.isConnected

    /** Whether Disconnect or removing the app from recents may stop it, and so whether Disconnect is offered. */
    fun operatorMayStop(active: Vehicle?): Boolean = active == null || !active.isLive || !active.state.armed

    /** Ends with no live vehicle, or once a disarmed one is silent for the recording's limit (REQ-036). */
    fun endsFor(active: Vehicle?): Boolean = when {
        active == null || !active.isLive -> true
        active.state.armed -> false
        else -> recording.closesAfter(active.lastHeartbeat.elapsedNow())
    }

    private val Vehicle.isLive: Boolean
        get() = origin == DataOrigin.LIVE

    private val Vehicle.isConnected: Boolean
        get() = link == VehicleLink.CONNECTED || linkLoss.linkAfter(lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED
}
