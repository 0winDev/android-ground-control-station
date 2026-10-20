package com.owindev.gcs.core.domain.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink

/**
 * When a replay must not start (REQ-037): it closes the live link, so this GCS stops sending its HEARTBEAT and the
 * vehicle's GCS failsafe (`FS_GCS_ENABLE`) may act. Refused while the live active vehicle is armed, heard or not, and
 * while it is connected by either the watchdog's verdict or [linkLoss] applied to its age now, so a watchdog late
 * after the device slept never lets a replay through.
 */
class ReplayRule(private val linkLoss: LinkLossRule) {

    /** Why a replay must not start with [activeVehicle] as the active one, or `null` if it may. */
    fun refusalFor(activeVehicle: Vehicle?): ReplayRefusal? = when {
        activeVehicle == null || activeVehicle.origin != DataOrigin.LIVE -> null

        activeVehicle.state.armed -> ReplayRefusal.VEHICLE_ARMED

        activeVehicle.link == VehicleLink.CONNECTED -> ReplayRefusal.VEHICLE_CONNECTED

        linkLoss.linkAfter(activeVehicle.lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED ->
            ReplayRefusal.VEHICLE_CONNECTED

        else -> null
    }
}
