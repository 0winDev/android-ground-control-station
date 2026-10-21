package com.owindev.gcs.core.domain.geo

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNear
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("REQ-035")
internal class GreatCircleTest {

    private lateinit var greatCircle: GreatCircle

    @BeforeEach
    fun setUp() {
        greatCircle = GreatCircle
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceDistances")
    fun `GIVEN two positions WHEN measuring the distance THEN matches GeodSolve on the same sphere`(
        reference: ReferenceDistance,
    ) {
        val from = reference.from
        val to = reference.to

        val result = greatCircle.distance(from.first, from.second, to.first, to.second)

        result.shouldBeNear(reference.metres, MICROMETRE)
    }

    @Test
    fun `GIVEN a gap across 180 degrees WHEN measuring it THEN equals the same gap across 0 degrees`() {
        val acrossPrimeMeridian = greatCircle.distance(TEN_NORTH, -GAP, TEN_NORTH, GAP)

        val result = greatCircle.distance(TEN_NORTH, 180 - GAP, TEN_NORTH, -180 + GAP)

        result.shouldBeNear(acrossPrimeMeridian, MICROMETRE)
    }

    @Test
    fun `GIVEN the same position WHEN measuring the distance THEN is zero`() {
        val (latitude, longitude) = CANBERRA

        val result = greatCircle.distance(latitude, longitude, latitude, longitude)

        result shouldBeEqualTo 0.0
    }

    private companion object {

        const val MICROMETRE = 1e-6
        const val TEN_NORTH = 10.0
        const val GAP = 0.00001
        val CANBERRA = -35.3633514 to 149.1652411

        /** `GeodSolve -i -e 6371008.8 0 -p 9` of GeographicLib 2.7: the exact geodesic of a sphere of radius R₁. */
        @JvmStatic
        fun referenceDistances(): List<ReferenceDistance> = listOf(
            ReferenceDistance("1° of latitude at the equator", 0.0 to 0.0, 1.0 to 0.0, 111_195.080233533),
            ReferenceDistance("2.1 m east in Canberra", CANBERRA, -35.3633514 to 149.1652643, 2.103761691),
            ReferenceDistance("1.9 m south in Canberra", CANBERRA, -35.3633685 to 149.1652411, 1.901435873),
            ReferenceDistance("2.1 m south in Canberra", CANBERRA, -35.3633703 to 149.1652411, 2.101587017),
            ReferenceDistance("a SITL flight's leg", CANBERRA, -35.3622 to 149.1601, 483.455848785),
            ReferenceDistance("2.2 m across 180°", TEN_NORTH to 180 - GAP, TEN_NORTH to -180 + GAP, 2.190115543),
            ReferenceDistance("pole to pole", 90.0 to 0.0, -90.0 to 0.0, 20_015_114.442035925),
            ReferenceDistance("the north pole at two longitudes", 90.0 to 0.0, 90.0 to 120.0, 0.0),
            ReferenceDistance("Canberra to Madrid", CANBERRA, 40.4168 to -3.7038, 17_579_247.691630092),
            ReferenceDistance("antipodes on the equator", 0.0 to 0.0, 0.0 to 180.0, 20_015_114.442035925),
        )
    }
}

/** From and to as latitude to longitude, in degrees. */
internal data class ReferenceDistance(
    val name: String,
    val from: Pair<Double, Double>,
    val to: Pair<Double, Double>,
    val metres: Double,
) {
    override fun toString() = name
}
