package com.owindev.gcs.core.domain.geo

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Short distances on a local plane scaled by WGS84's radii of curvature at the mean latitude of the two positions
 * (REQ-055): north = Δφ · M(φₘ), east = Δλ · N(φₘ) · cos φₘ, where M = a(1 − e²) / W³ is the meridional radius,
 * N = a / W the prime vertical one and W = √(1 − e² sin² φₘ). Δλ is taken the short way round, so crossing ±180°
 * needs no special case.
 *
 * Up to 500 m it is within 0.005 mm of the WGS84 geodesic at every bearing between 80°S and 80°N
 * (`tools/geo/route_reference.py`, GeographicLib 2.1); radii at the first position's latitude instead would be 7.5 mm
 * off at 45° and 21 mm at 70°. Nearer the poles it only overestimates, the safe side for a geofence. It is for
 * distances within a route's geofence, not for long ones.
 */
internal object LocalPlane {

    /** WGS84's semi-major axis a, in m (NGA.STND.0036, 2014). */
    private const val SEMI_MAJOR_AXIS_METRES = 6_378_137.0

    /** WGS84's flattening f = 1 / 298.257223563; e² = f (2 − f). */
    private const val FLATTENING = 1 / 298.257223563
    private const val ECCENTRICITY_SQUARED = FLATTENING * (2 - FLATTENING)

    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360.0

    /** The distance in m between two WGS84 positions in degrees, a few kilometres apart at most. */
    fun distance(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
        val meanPhi = Math.toRadians((fromLatitude + toLatitude) / 2)
        val sinPhi = sin(meanPhi)
        val w = sqrt(1 - ECCENTRICITY_SQUARED * sinPhi * sinPhi)
        val meridional = SEMI_MAJOR_AXIS_METRES * (1 - ECCENTRICITY_SQUARED) / (w * w * w)
        val primeVertical = SEMI_MAJOR_AXIS_METRES / w
        val deltaLongitude =
            (toLongitude - fromLongitude + HALF_TURN_DEGREES).mod(FULL_TURN_DEGREES) - HALF_TURN_DEGREES
        val north = Math.toRadians(toLatitude - fromLatitude) * meridional
        val east = Math.toRadians(deltaLongitude) * primeVertical * cos(meanPhi)
        return hypot(north, east)
    }
}
