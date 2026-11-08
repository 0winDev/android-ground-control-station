package com.owindev.gcs.core.domain.link

import kotlinx.coroutines.flow.StateFlow

/**
 * The uplink to the active vehicle as this GCS observes it. Implemented in `:data:vehicle` and bound there with
 * Hilt.
 */
interface UplinkRepository {

    /**
     * Reset to [UplinkHealth.HEALTHY] when the vehicle is LOST and when a session ends (REQ-045). Healthy means
     * nothing observed against the uplink, never that the vehicle hears this GCS.
     */
    val health: StateFlow<UplinkHealth>
}
