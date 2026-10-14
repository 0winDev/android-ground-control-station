package com.owindev.gcs.core.domain.geo

import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Expected values come from `geo/mgrs-reference.csv` (GeographicLib GeoConvert and NGA GEOTRANS), never from our code. */
@Tag("REQ-027")
internal class MgrsTest {

    private lateinit var toMgrs: (Double, Double) -> Mgrs

    @BeforeEach
    fun setUp() {
        toMgrs = ::mgrsOf
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referencePoints")
    fun `GIVEN a reference point WHEN converting it to MGRS THEN matches both references`(point: ReferencePoint) {
        val expected = point.mgrs ?: NOT_AVAILABLE

        val result = toMgrs(point.latitude, point.longitude)

        result.compact() shouldBeEqualTo expected
    }

    @Test
    fun `GIVEN a point 1 cm under a 1 m boundary WHEN converting it THEN the metres are truncated, not rounded`() {
        val latitude = 40.65497342994393
        val longitude = 3.00146666798817

        val result = toMgrs(latitude, longitude)

        result shouldBeEqualTo Mgrs.Grid(zone = 31, band = 'T', square = "EF", easting = 123, northing = 456)
    }

    @Test
    fun `GIVEN a point 1 cm under a 100 km boundary WHEN converting it THEN stays in its square`() {
        val latitude = 41.54541357158871
        val longitude = 4.19896616551278

        val result = toMgrs(latitude, longitude)

        result shouldBeEqualTo Mgrs.Grid(zone = 31, band = 'T', square = "EF", easting = 99_999, northing = 99_999)
    }

    @Test
    fun `GIVEN columns on each side of the skipped O WHEN converting them THEN the letters skip it`() {
        val beforeO = 40.64934660601691 to 9.59140070065588
        val afterO = 40.63723548121438 to 10.77383086975895

        val result = listOf(beforeO, afterO).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("32TNL5000000000", "32TPL5000000000")
    }

    @Test
    fun `GIVEN rows on each side of the skipped O WHEN converting them THEN the letters skip it`() {
        val beforeO = 11.30768360317091 to 3.00000458173441
        val afterO = 12.21203745071005 to 3.00000459671769

        val result = listOf(beforeO, afterO).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("31PEN0000050000", "31PEP0000050000")
    }

    @Test
    fun `GIVEN 56N on each side of 3E WHEN converting it THEN east of 3E is in 32V, the Norway exception`() {
        val westOf3 = 56.0 to 2.9999999
        val at3 = 56.0 to 3.0

        val result = listOf(westOf3, at3).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("31VDC9999906079", "32VJH2604922336")
    }

    @Test
    fun `GIVEN 78N on each side of 9E WHEN converting it THEN the zones are 31X and 33X, the Svalbard exception`() {
        val westOf9 = 78.0 to 8.9999999
        val at9 = 78.0 to 9.0

        val result = listOf(westOf9, at9).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("31XFG3902665496", "33XUG6097365496")
    }

    @Test
    fun `GIVEN 72N WHEN converting it THEN the band is X`() {
        val latitude = 72.0
        val longitude = 25.0

        val result = toMgrs(latitude, longitude)

        result.compact() shouldBeEqualTo "35XMV3103090077"
    }

    @Test
    fun `GIVEN 180E and 180W WHEN converting them THEN both are zone 1`() {
        val east = 10.0 to 180.0
        val west = 10.0 to -180.0

        val result = listOf(east, west).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("01PAM7107106908", "01PAM7107106908")
    }

    @Test
    fun `GIVEN the limits of UTM WHEN converting them THEN 80S is a square and 84N is not available`() {
        val south = -80.0 to 10.0
        val north = 84.0 to 25.0

        val result = listOf(south, north).map { (latitude, longitude) -> toMgrs(latitude, longitude).compact() }

        result shouldBeEqualTo listOf("32CNS1938418247", NOT_AVAILABLE)
    }

    @Test
    fun `GIVEN positions that are not valid WHEN converting them THEN none is available`() {
        val invalid = listOf(
            Double.NaN to 10.0,
            10.0 to Double.NaN,
            10.0 to Double.POSITIVE_INFINITY,
            OUT_OF_RANGE_LATITUDE to 10.0,
            -OUT_OF_RANGE_LATITUDE to 10.0,
        )

        val result = invalid.map { (latitude, longitude) -> toMgrs(latitude, longitude) }

        result shouldBeEqualTo List(invalid.size) { Mgrs.NotAvailable }
    }

    @Test
    fun `GIVEN a square WHEN printing it THEN shows zone and band, square, easting and northing with leading zeros`() {
        val square = Mgrs.Grid(zone = 1, band = 'P', square = "AM", easting = 71_071, northing = 6_908)

        val result = square.toString()

        result shouldBeEqualTo "01P AM 71071 06908"
    }

    @Test
    fun `GIVEN the SITL home position WHEN converting the vehicle position THEN matches both references`() {
        val position = GlobalPosition(
            latitude = -35.3633514,
            longitude = 149.1652411,
            altitudeMsl = 584.0,
            altitudeAboveHome = 0.0,
            velocityNorth = 0.0,
            velocityEast = 0.0,
            velocityDown = 0.0,
            heading = null,
        )

        val result = position.toMgrs()

        result.compact() shouldBeEqualTo "55HFA9672084509"
    }

    private companion object {

        const val NOT_AVAILABLE = "NOT_AVAILABLE"
        const val OUT_OF_RANGE_LATITUDE = 91.0

        @JvmStatic
        fun referencePoints(): List<ReferencePoint> = REFERENCE_POINTS

        /** The references' format: no spaces, or NOT_AVAILABLE. */
        fun Mgrs.compact(): String = when (this) {
            is Mgrs.Grid -> toString().replace(" ", "")
            Mgrs.NotAvailable -> NOT_AVAILABLE
        }
    }
}
