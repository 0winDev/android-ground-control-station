package com.owindev.gcs.core.domain.geo

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNear
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("REQ-027")
internal class UtmTest {

    private lateinit var projection: UtmProjection

    @BeforeEach
    fun setUp() {
        projection = UtmProjection
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pointsInUtm")
    fun `GIVEN a reference point in UTM WHEN projecting it THEN matches GeoConvert within a millimetre`(
        point: ReferencePoint,
    ) {
        val expected = checkNotNull(point.utm)

        val result = projection.utmOf(point.latitude, point.longitude)

        result.shouldNotBeNull()
        result.zone shouldBeEqualTo expected.zone
        result.north shouldBeEqualTo expected.north
        result.easting.shouldBeNear(expected.easting, MILLIMETRE)
        result.northing.shouldBeNear(expected.northing, MILLIMETRE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pointsOutsideUtm")
    fun `GIVEN a reference point outside UTM WHEN projecting it THEN there is no UTM coordinate`(
        point: ReferencePoint,
    ) {
        val result = projection.utmOf(point.latitude, point.longitude)

        result.shouldBeNull()
    }

    private companion object {

        const val MILLIMETRE = 0.001

        @JvmStatic
        fun pointsInUtm(): List<ReferencePoint> = REFERENCE_POINTS.filter { it.utm != null }

        @JvmStatic
        fun pointsOutsideUtm(): List<ReferencePoint> = REFERENCE_POINTS.filter { it.utm == null }
    }
}
