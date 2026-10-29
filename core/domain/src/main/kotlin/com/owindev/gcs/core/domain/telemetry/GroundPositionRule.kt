package com.owindev.gcs.core.domain.telemetry

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A second sign that a vehicle is on the ground, independent of its landed state ([LandedRule]), so a take off needs
 * both (REQ-044): a position younger than [maxAge] puts it at most [maxAltitudeAboveHome] above home and moving
 * vertically at most [maxVerticalSpeed]. A missing or older position never counts.
 */
class GroundPositionRule(
    val maxAge: Duration = DEFAULT_MAX_AGE,
    val maxAltitudeAboveHome: Double = DEFAULT_MAX_ALTITUDE_ABOVE_HOME,
    val maxVerticalSpeed: Double = DEFAULT_MAX_VERTICAL_SPEED,
) {

    init {
        require(maxAge > Duration.ZERO) { "The position's maximum age must be positive, was $maxAge" }
        require(maxAltitudeAboveHome > 0.0 && maxVerticalSpeed > 0.0) {
            "The ground limits must be positive, were $maxAltitudeAboveHome m and $maxVerticalSpeed m/s"
        }
    }

    fun isNearGround(telemetry: Telemetry?): Boolean {
        val position = telemetry?.position ?: return false
        return position.receivedAt.elapsedNow() < maxAge &&
            position.value.altitudeAboveHome <= maxAltitudeAboveHome &&
            abs(position.value.velocityDown) <= maxVerticalSpeed
    }

    companion object {
        /** Two and a half intervals of the 5 Hz GLOBAL_POSITION_INT is requested at, like [LandedRule]'s 0.5 s. */
        val DEFAULT_MAX_AGE: Duration = 500.milliseconds

        /**
         * ArduCopter sets home where it arms (#69), so a copter armed on the ground reads within centimetres of it
         * (0.0 to 0.1 m in the #112 SITL capture); 1 m leaves room for barometer drift and is no height to take off
         * from by mistake.
         */
        const val DEFAULT_MAX_ALTITUDE_ABOVE_HOME = 1.0

        /**
         * Half of what ArduCopter's land detector still calls landed (|vz| < 1 m/s, `LAND_DETECTOR_VEL_Z_MAX`,
         * Copter-4.7.2) and a fifth of a pilot's default climb (`PILOT_SPD_UP` 2.5 m/s).
         */
        const val DEFAULT_MAX_VERTICAL_SPEED = 0.5
    }
}
