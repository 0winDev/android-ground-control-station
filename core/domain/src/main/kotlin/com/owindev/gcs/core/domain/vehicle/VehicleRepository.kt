package com.owindev.gcs.core.domain.vehicle

import kotlinx.coroutines.flow.StateFlow

/** The vehicles heard on the link. Implemented in `:data:vehicle`, bound in `:app`. */
interface VehicleRepository {

    /**
     * Every vehicle heard since the link was opened, keyed by its system ID: a vehicle's state is only
     * ever replaced by HEARTBEATs from that same system ID (REQ-016).
     */
    val vehicles: StateFlow<Map<SystemId, VehicleState>>
}
