package com.owindev.gcs.core.domain.geo

import kotlin.math.IEEErem
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * A UTM coordinate (REQ-027).
 *
 * @property zone 1 to 60.
 * @property north `true` in the northern hemisphere, where the false northing is 0.
 * @property easting in m, false easting (500 km) included.
 * @property northing in m, false northing (10 000 km in the south) included.
 */
internal data class Utm(val zone: Int, val north: Boolean, val easting: Double, val northing: Double)

/**
 * WGS84 latitude/longitude to UTM, as GeographicLib 2.7 does it (`UTMUPS::StandardZone`, `UTMUPS::Forward` and the
 * 6th-order `TransverseMercator::Forward`): the transverse Mercator projection with Krüger's series in the third
 * flattening, from C. F. F. Karney, "Transverse Mercator with an accuracy of a few nanometers", J. Geodesy 85(8),
 * 475–485 (2011), doi:10.1007/s00190-011-0445-3. About 5 nm of error across UTM (GeographicLib's own figure for the
 * 6th order), far below the 1 m MGRS needs.
 */
internal object UtmProjection {

    /** WGS84 semi-major axis in m and flattening (GeographicLib `Constants::WGS84_a`, `WGS84_f`). */
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563

    /** UTM central scale factor (GeographicLib `Constants::UTM_k0`, 9996/10000). */
    private const val K0 = 0.9996

    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0

    /** UTM covers −80° ≤ latitude < 84° (GeographicLib `UTMUPS::StandardZone`); beyond is UPS. */
    private const val SOUTH_LIMIT = -80.0
    private const val NORTH_LIMIT = 84.0

    private const val ZONE_WIDTH = 6
    private const val DEGREES_PER_TURN = 360.0
    private const val HALF_TURN = 180

    /** Latitude band indices as in GeographicLib `MGRS::LatitudeBand`: −10 (C) up to 9 (X), 8° each. */
    private const val BAND_C = -10
    private const val BAND_V = 7
    private const val BAND_X = 9
    private const val BAND_HEIGHT = 8

    /** zone = (whole degrees east of 180°W) / 6 + 1, written as (degrees + 186) / 6 with degrees in [−180, 180). */
    private const val ZONE_OFFSET = HALF_TURN + ZONE_WIDTH

    /** Norway: 31V east of 3°E belongs to 32V. */
    private const val NORWAY_ZONE = 31
    private const val NORWAY_EAST_OF = 3

    /** Svalbard (band X, 0° to 42°E): zones 31, 33, 35 and 37, each 12° wide and centred on the odd zone. */
    private const val SVALBARD_EAST_LIMIT = 42
    private const val SVALBARD_ZONE_WIDTH = 12
    private const val SVALBARD_OFFSET = HALF_TURN + ZONE_WIDTH / 2

    /** e² = f(2 − f), e and n = f/(2 − f), the third flattening. */
    private const val E2 = F * (2 - F)
    private val E = sqrt(E2)
    private const val N = F / (2 - F)

    /**
     * The rectifying radius A = a/(1 + n) · (1 + n²/4 + n⁴/64 + n⁶/256): the radius of the sphere whose meridian
     * has the ellipsoid's length (GeographicLib `b1coeff`, order 6, times a).
     */
    private val RECTIFYING_RADIUS = A / (1 + N) * horner(doubleArrayOf(1.0, 4.0, 64.0, 256.0), N * N) / 256

    /**
     * Krüger's α₁…α₆, each a polynomial in n divided by a constant, times nʲ (GeographicLib `alpcoeff`, order 6:
     * coefficients from the highest power down, the divisor last). α₁ = n/2 − 2n²/3 + 5n³/16 + …
     */
    private val ALPHA_POLYNOMIALS = arrayOf(
        doubleArrayOf(31_564.0, -66_675.0, 34_440.0, 47_250.0, -100_800.0, 75_600.0, 151_200.0),
        doubleArrayOf(-1_983_433.0, 863_232.0, 748_608.0, -1_161_216.0, 524_160.0, 1_935_360.0),
        doubleArrayOf(670_412.0, 406_647.0, -533_952.0, 184_464.0, 725_760.0),
        doubleArrayOf(6_601_661.0, -7_732_800.0, 2_230_245.0, 7_257_600.0),
        doubleArrayOf(-13_675_556.0, 3_438_171.0, 7_983_360.0),
        doubleArrayOf(212_378_941.0, 319_334_400.0),
    )

    /** αⱼ = nʲ · polynomialⱼ(n) / divisorⱼ, j = 1…6. */
    private val ALPHA: DoubleArray = run {
        var power = 1.0
        DoubleArray(ALPHA_POLYNOMIALS.size) { index ->
            power *= N
            val coefficients = ALPHA_POLYNOMIALS[index]
            power * horner(coefficients.copyOf(coefficients.size - 1), N) / coefficients.last()
        }
    }

    /** The UTM coordinate of a WGS84 position, or `null` outside UTM coverage (polar regions, NaN). */
    fun utmOf(latitude: Double, longitude: Double): Utm? {
        if (!(latitude >= SOUTH_LIMIT && latitude < NORTH_LIMIT) || !longitude.isFinite()) return null
        val zone = zoneOf(latitude, longitude)
        val centralMeridian = (ZONE_WIDTH * zone - HALF_TURN - ZONE_WIDTH / 2).toDouble()
        val (x, y) = transverseMercator(latitude, (longitude - centralMeridian).IEEErem(DEGREES_PER_TURN))
        // The hemisphere follows the sign bit, so −0.0 is south, as in GeographicLib.
        val north = !(latitude < 0 || 1 / latitude < 0)
        return Utm(
            zone = zone,
            north = north,
            easting = x + FALSE_EASTING,
            northing = if (north) y else y + FALSE_NORTHING_SOUTH,
        )
    }

    /**
     * Latitude band index, −10 (C) up to 9 (X): 8° bands from 80°S, each including its southern edge, and X
     * stretched to 84°N (GeographicLib `MGRS::LatitudeBand`).
     */
    fun latitudeBand(latitude: Double): Int =
        ((floor(latitude).toInt() - SOUTH_LIMIT.toInt()) / BAND_HEIGHT + BAND_C).coerceIn(BAND_C, BAND_X)

    /**
     * The standard zone: 6° from 180°W, except 32V west of 3°E in Norway and the 31X/33X/35X/37X widths around
     * Svalbard (GeographicLib `UTMUPS::StandardZone`).
     */
    private fun zoneOf(latitude: Double, longitude: Double): Int {
        var degrees = floor(longitude.IEEErem(DEGREES_PER_TURN)).toInt()
        if (degrees == HALF_TURN) degrees = -HALF_TURN
        val band = latitudeBand(latitude)
        return when {
            band == BAND_V && (degrees + ZONE_OFFSET) / ZONE_WIDTH == NORWAY_ZONE && degrees >= NORWAY_EAST_OF ->
                NORWAY_ZONE + 1

            band == BAND_X && degrees in 0 until SVALBARD_EAST_LIMIT ->
                2 * ((degrees + SVALBARD_OFFSET) / SVALBARD_ZONE_WIDTH) + 1

            else -> (degrees + ZONE_OFFSET) / ZONE_WIDTH
        }
    }

    /**
     * Transverse Mercator with Krüger's series, x (east) and y (north) in m from the central meridian and the
     * equator, for [latitude] and [deltaLongitude] in degrees (GeographicLib `TransverseMercator::Forward`).
     */
    private fun transverseMercator(latitude: Double, deltaLongitude: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latitude)
        val lambda = Math.toRadians(deltaLongitude)
        // τ′ = tan φ′, the conformal latitude, from τ = tan φ (GeographicLib `Math::taupf`).
        val tau = sin(phi) / cos(phi)
        val tauPrime = conformalTangent(tau)
        // Gauss–Schreiber coordinates ξ′, η′ on the conformal sphere.
        val xiPrime = atan2(tauPrime, cos(lambda))
        val etaPrime = asinh(sin(lambda) / hypot(tauPrime, cos(lambda)))
        // Krüger: ξ = ξ′ + Σ αⱼ sin 2jξ′ cosh 2jη′, η = η′ + Σ αⱼ cos 2jξ′ sinh 2jη′.
        var xi = xiPrime
        var eta = etaPrime
        for (j in 1..ALPHA.size) {
            xi += ALPHA[j - 1] * sin(2 * j * xiPrime) * cosh(2 * j * etaPrime)
            eta += ALPHA[j - 1] * cos(2 * j * xiPrime) * sinh(2 * j * etaPrime)
        }
        return K0 * RECTIFYING_RADIUS * eta to K0 * RECTIFYING_RADIUS * xi
    }

    /** τ′ = τ √(1 + σ²) − σ √(1 + τ²), σ = sinh(e · atanh(e τ / √(1 + τ²))) (GeographicLib `Math::taupf`). */
    private fun conformalTangent(tau: Double): Double {
        val secant = hypot(1.0, tau)
        val sigma = sinh(E * atanh(E * tau / secant))
        return hypot(1.0, sigma) * tau - sigma * secant
    }

    /** Evaluates the polynomial with [coefficients] from the highest power down at [x] (Horner). */
    private fun horner(coefficients: DoubleArray, x: Double): Double =
        coefficients.fold(0.0) { sum, coefficient -> sum * x + coefficient }
}
