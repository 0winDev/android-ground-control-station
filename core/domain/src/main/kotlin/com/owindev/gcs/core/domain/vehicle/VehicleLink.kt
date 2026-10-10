package com.owindev.gcs.core.domain.vehicle

/** Whether a vehicle is still being heard, as decided by [LinkLossRule]. */
enum class VehicleLink {
    CONNECTED,
    LOST,
}
