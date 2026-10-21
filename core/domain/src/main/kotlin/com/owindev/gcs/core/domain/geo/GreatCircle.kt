package com.owindev.gcs.core.domain.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distances along the great circle of a sphere with the Earth's mean radius (REQ-035), by the haversine formula of
 * R. W. Sinnott, "Virtues of the Haversine", Sky & Telescope 68(2), 159 (1984):
 * h = sin²(Δφ/2) + cos φ₁ · cos φ₂ · sin²(Δλ/2), d = 2R · atan2(√h, √(1 − h)).
 *
 * The atan2 form keeps full precision up to antipodal points, where asin(√h) loses it; sin²(Δλ/2) is the same for
 * Δλ and Δλ ± 360°, so crossing ±180° needs no wrapping. A sphere is off the WGS84 geodesic by less than 0.6 %
 * (GeographicLib 2.7 GeodSolve: 1° of latitude at the equator is 111 195 m on it, 110 574 m on WGS84), well enough to
 * space trail points a few metres apart; it is not for navigation.
 */
internal object GreatCircle {

    /** IUGG mean radius R₁ = (2a + b) / 3 of GRS80, in m (H. Moritz, "Geodetic Reference System 1980"). */
    const val MEAN_RADIUS_METRES = 6_371_008.8

    /** The distance in m between two WGS84 positions in degrees. */
    fun distance(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
        val fromPhi = Math.toRadians(fromLatitude)
        val toPhi = Math.toRadians(toLatitude)
        val halfDeltaPhi = (toPhi - fromPhi) / 2
        val halfDeltaLambda = Math.toRadians(toLongitude - fromLongitude) / 2
        val sinHalfPhi = sin(halfDeltaPhi)
        val sinHalfLambda = sin(halfDeltaLambda)
        // Rounding can put h a few ulps past 1 between antipodes, where √(1 − h) would be NaN.
        val h = (sinHalfPhi * sinHalfPhi + cos(fromPhi) * cos(toPhi) * sinHalfLambda * sinHalfLambda).coerceIn(0.0, 1.0)
        return 2 * MEAN_RADIUS_METRES * atan2(sqrt(h), sqrt(1 - h))
    }
}
