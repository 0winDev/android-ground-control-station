package com.owindev.gcs.core.domain.vehicle

import kotlinx.coroutines.flow.StateFlow

/** The vehicles heard on the link. Implemented in `:data:vehicle`, bound in `:app`. */
interface VehicleRepository {

    /**
     * Every vehicle heard since the app started, keyed by its system ID: a vehicle is only ever updated
     * by HEARTBEATs from that same system ID (REQ-016).
     *
     * A vehicle that stops being heard is kept, [VehicleLink.LOST] with its last known state (REQ-017),
     * and comes back to [VehicleLink.CONNECTED] with its next HEARTBEAT (REQ-019).
     */
    val vehicles: StateFlow<Map<SystemId, Vehicle>>
}
