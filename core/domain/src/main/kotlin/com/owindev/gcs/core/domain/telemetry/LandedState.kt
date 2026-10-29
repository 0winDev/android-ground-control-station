package com.owindev.gcs.core.domain.telemetry

/**
 * Whether the vehicle is on the ground, as `MAV_LANDED_STATE` defines it
 * (https://mavlink.io/en/messages/common.html#MAV_LANDED_STATE).
 */
enum class LandedState {
    ON_GROUND,
    TAKING_OFF,
    IN_AIR,
    LANDING,

    /** `MAV_LANDED_STATE_UNDEFINED`, or a value the enum does not define. */
    UNKNOWN,
}
