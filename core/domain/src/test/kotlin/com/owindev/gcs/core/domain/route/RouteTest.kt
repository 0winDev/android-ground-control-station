package com.owindev.gcs.core.domain.route

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-055")
class RouteTest {

    @Test
    fun `GIVEN no radius WHEN building a route THEN its geofence is 500 m`() {
        val result = Route(items = emptyList()).geofenceRadiusMetres

        result shouldBeEqualTo 500.0
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY])
    fun `GIVEN a radius not positive or not finite WHEN building a route THEN is refused`(radius: Double) {
        val build = { Route(items = emptyList(), geofenceRadiusMetres = radius) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }
}
