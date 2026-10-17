package com.owindev.gcs.core.domain.vehicle

import kotlinx.coroutines.flow.StateFlow

/** The vehicles heard on the link. Implemented in `:data:vehicle` and bound there with Hilt. */
interface VehicleRepository {

    /**
     * Every vehicle heard since the app started, keyed by its system ID: a vehicle is only ever updated
     * by HEARTBEATs from that same system ID (REQ-016).
     *
     * A vehicle that stops being heard is kept, [VehicleLink.LOST] with its last known state (REQ-017),
     * and comes back to [VehicleLink.CONNECTED] with its next HEARTBEAT (REQ-019).
     */
    val vehicles: StateFlow<Map<SystemId, Vehicle>>

    /**
     * The vehicle the operator is working with: the first one heard since the app started, `null` until
     * then (REQ-020). It never changes on its own, not when a vehicle with a lower system ID appears nor
     * when this one's link is lost; any other vehicle in [vehicles] is shown apart, never merged into it.
     *
     * It is decided from the same HEARTBEATs as [vehicles], so it only fills while [vehicles] is observed.
     */
    val activeSystemId: StateFlow<SystemId?>
}
