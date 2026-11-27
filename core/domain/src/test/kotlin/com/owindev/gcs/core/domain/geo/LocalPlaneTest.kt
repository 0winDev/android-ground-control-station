package com.owindev.gcs.core.domain.geo

import org.amshove.kluent.shouldBeNear
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("REQ-055")
internal class LocalPlaneTest {

    private lateinit var plane: LocalPlane

    @BeforeEach
    fun setUp() {
        plane = LocalPlane
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("references")
    fun `GIVEN two positions up to 500 m apart WHEN measuring the distance THEN matches GeographicLib on WGS84`(
        reference: Reference,
    ) {
        val (from, to) = reference.from to reference.to

        val result = plane.distance(from.first, from.second, to.first, to.second)

        result.shouldBeNear(reference.metres, TENTH_OF_A_MILLIMETRE)
    }

    @Test
    fun `GIVEN a gap across 180 degrees WHEN measuring it THEN equals the same gap across 0 degrees`() {
        val acrossPrimeMeridian = plane.distance(TEN_NORTH, -GAP, TEN_NORTH, GAP)

        val result = plane.distance(TEN_NORTH, 180 - GAP, TEN_NORTH, -180 + GAP)

        result.shouldBeNear(acrossPrimeMeridian, TENTH_OF_A_MILLIMETRE)
    }

    data class Reference(
        val name: String,
        val from: Pair<Double, Double>,
        val to: Pair<Double, Double>,
        val metres: Double,
    ) {
        override fun toString(): String = name
    }

    private companion object {
        // tools/geo/route_reference.py: a point put by GeographicLib 2.1's Geodesic.Direct, rounded to 1e-7 degrees,
        // and its distance back by Geodesic.Inverse on WGS84.
        @JvmStatic
        fun references(): List<Reference> = listOf(
            Reference("equator 45° 100 m", 0.0 to 10.0, 0.0006395 to 10.0006352, 100.000731),
            Reference("SITL 90° 499.99 m", SITL, -35.3632620 to 149.1707389, 499.992239),
            Reference("SITL 0° 500.01 m", SITL, -35.3587554 to 149.1652374, 500.005747),
            Reference("SITL 180° 500.01 m", SITL, -35.3677688 to 149.1652374, 500.006121),
            Reference("45N 45° 499.99 m", 45.0 to 7.0, 45.0031812 to 7.0044842, 499.986511),
            Reference("70N 45° 500.01 m", 70.0 to 25.0, 70.0031689 to 25.0092602, 500.007317),
            Reference("70N 90° 499.99 m", 70.0 to 25.0, 69.9999995 to 25.0130934, 499.991668),
            Reference("antimeridian 90° 500.01 m", ANTIMERIDIAN, 10.0 to -179.9955395, 500.010320),
            Reference("antimeridian 45° 499.99 m", ANTIMERIDIAN, 10.0031964 to -179.9968753, 499.994515),
        )

        val SITL = -35.3632621 to 149.1652374
        val ANTIMERIDIAN = 10.0 to 179.9999
        const val TENTH_OF_A_MILLIMETRE = 1e-4
        const val TEN_NORTH = 10.0
        const val GAP = 0.002
    }
}
