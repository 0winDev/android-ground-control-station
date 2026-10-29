package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@Tag("REQ-044")
class GroundPositionRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: GroundPositionRule

    @BeforeEach
    fun setUp() {
        rule = GroundPositionRule()
    }

    @Test
    fun `GIVEN the default rule WHEN reading it THEN 0,5 s, 1 m above home and 0,5 m per s`() {
        val result =
            listOf(rule.maxAge.inWholeMilliseconds.toDouble(), rule.maxAltitudeAboveHome, rule.maxVerticalSpeed)

        result shouldBeEqualTo listOf(500.0, 1.0, 0.5)
    }

    @ParameterizedTest
    @CsvSource("0.0, 0.0", "1.0, 0.0", "-1.5, 0.0", "0.0, 0.5", "0.0, -0.5")
    fun `GIVEN a fresh position at most 1 m above home and slow WHEN checking THEN is near the ground`(
        altitudeAboveHome: Double,
        velocityDown: Double,
    ) {
        val telemetry = positioned(altitudeAboveHome, velocityDown)

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a position just over 1 m above home WHEN checking THEN is not near the ground`() {
        val telemetry = positioned(altitudeAboveHome = 1.01, velocityDown = 0.0)

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo false
    }

    @ParameterizedTest
    @CsvSource("-0.51", "0.51")
    fun `GIVEN a vertical speed just over 0,5 m per s WHEN checking THEN is not near the ground`(velocityDown: Double) {
        val telemetry = positioned(altitudeAboveHome = 0.0, velocityDown = velocityDown)

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a ground position just under 0,5 s old WHEN checking THEN is near the ground`() {
        val telemetry = positioned(altitudeAboveHome = 0.0, velocityDown = 0.0)
        clock += MAX_AGE - 1.milliseconds

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a ground position exactly 0,5 s old WHEN checking THEN is not near the ground`() {
        val telemetry = positioned(altitudeAboveHome = 0.0, velocityDown = 0.0)
        clock += MAX_AGE

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN telemetry without a position WHEN checking THEN is not near the ground`() {
        val telemetry = Telemetry(origin = DataOrigin.LIVE)

        val result = rule.isNearGround(telemetry)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a limit of zero WHEN building the rule THEN it is refused`() {
        val build = { GroundPositionRule(maxVerticalSpeed = 0.0) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }

    private fun positioned(altitudeAboveHome: Double, velocityDown: Double) = Telemetry(
        position = Timed(
            GlobalPosition(
                latitude = -35.3632621,
                longitude = 149.1652374,
                altitudeMsl = 584.0 + altitudeAboveHome,
                altitudeAboveHome = altitudeAboveHome,
                velocityNorth = 0.0,
                velocityEast = 0.0,
                velocityDown = velocityDown,
                heading = null,
            ),
            clock.markNow(),
        ),
        origin = DataOrigin.LIVE,
    )

    private companion object {
        val MAX_AGE = 500.milliseconds
    }
}
