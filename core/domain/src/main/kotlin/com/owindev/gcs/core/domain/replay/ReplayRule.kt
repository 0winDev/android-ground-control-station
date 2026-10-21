package com.owindev.gcs.core.domain.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink

/**
 * When a replay must not start (REQ-037): it closes the live link, so this GCS stops sending its HEARTBEAT and a
 * vehicle's GCS failsafe (`FS_GCS_ENABLE`) may act. Every live vehicle counts, not only the active one: the HEARTBEAT
 * is not targeted, so behind a router every vehicle at that address hears it. Refused while any of them is armed,
 * heard or not, and while any is connected by either the watchdog's verdict or [linkLoss] applied to its age now, so
 * a watchdog late after the device slept never lets a replay through. Armed is the reason given when both apply.
 */
class ReplayRule(private val linkLoss: LinkLossRule) {

    /** Why a replay must not start with these [vehicles] heard, or `null` if it may; replayed ones never count. */
    fun refusalFor(vehicles: Collection<Vehicle>): ReplayRefusal? {
        val live = vehicles.filter { it.origin == DataOrigin.LIVE }
        return when {
            live.any { it.state.armed } -> ReplayRefusal.VEHICLE_ARMED
            live.any(::isConnected) -> ReplayRefusal.VEHICLE_CONNECTED
            else -> null
        }
    }

    private fun isConnected(vehicle: Vehicle): Boolean = vehicle.link == VehicleLink.CONNECTED ||
        linkLoss.linkAfter(vehicle.lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED
}
