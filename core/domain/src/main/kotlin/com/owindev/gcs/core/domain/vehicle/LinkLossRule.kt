package com.owindev.gcs.core.domain.vehicle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When a vehicle's link counts as lost (REQ-017): after [timeout] without a HEARTBEAT from it.
 *
 * The timeout is the only rule. There is no hysteresis: a HEARTBEAT that arrives late but within the
 * timeout keeps the link CONNECTED, and any HEARTBEAT after a loss recovers it (REQ-019). The vehicle's
 * own reaction to losing the ground station (ArduPilot's `FS_GCS_ENABLE`) is independent of this rule.
 */
class LinkLossRule(val timeout: Duration = DEFAULT_TIMEOUT) {

    init {
        require(timeout > Duration.ZERO) { "The link-loss timeout must be positive, was $timeout" }
    }

    /** The link state [sinceLastHeartbeat] after the vehicle's last HEARTBEAT, measured on a monotonic clock. */
    fun linkAfter(sinceLastHeartbeat: Duration): VehicleLink =
        if (sinceLastHeartbeat >= timeout) VehicleLink.LOST else VehicleLink.CONNECTED

    companion object {
        /** REQ-017's default. */
        val DEFAULT_TIMEOUT: Duration = 3.seconds
    }
}
