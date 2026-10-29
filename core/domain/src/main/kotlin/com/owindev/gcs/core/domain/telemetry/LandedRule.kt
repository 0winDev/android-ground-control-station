package com.owindev.gcs.core.domain.telemetry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether a vehicle counts as on the ground (REQ-052): only on an [LandedState.ON_GROUND] younger than [maxAge].
 * Unknown, never received or older is never on the ground, so whatever needs the ground (a take off, #99) is refused,
 * never assumed. It says nothing about the link, the route or where the telemetry came from: a caller checks those
 * (REQ-043) and passes the active vehicle's telemetry.
 */
class LandedRule(val maxAge: Duration = DEFAULT_MAX_AGE) {

    init {
        require(maxAge > Duration.ZERO) { "The landed state's maximum age must be positive, was $maxAge" }
    }

    fun isOnGround(telemetry: Telemetry?): Boolean {
        val landed = telemetry?.landed ?: return false
        return landed.value == LandedState.ON_GROUND && landed.receivedAt.elapsedNow() < maxAge
    }

    companion object {
        /**
         * Two intervals of the 4 Hz it is requested at: a copter climbing by RC from the ground reads as on the ground
         * for at most this long after its last ON_GROUND (#112 safety review), one lost frame included.
         */
        val DEFAULT_MAX_AGE: Duration = 500.milliseconds
    }
}
