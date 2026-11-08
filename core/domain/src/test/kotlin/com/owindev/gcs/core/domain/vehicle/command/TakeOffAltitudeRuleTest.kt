package com.owindev.gcs.core.domain.vehicle.command

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-044")
class TakeOffAltitudeRuleTest {

    private lateinit var rule: TakeOffAltitudeRule

    @BeforeEach
    fun setUp() {
        rule = TakeOffAltitudeRule()
    }

    @Test
    fun `GIVEN the default rule WHEN reading it THEN 2 to 30 m and 10 m by default`() {
        val result = listOf(rule.minMetres, rule.maxMetres, rule.defaultMetres)

        result shouldBeEqualTo listOf(2f, 30f, 10f)
    }

    @ParameterizedTest
    @ValueSource(floats = [2f, 10f, 30f])
    fun `GIVEN an altitude within the range WHEN checking it THEN is valid`(metres: Float) {
        val result = rule.isValid(metres)

        result shouldBeEqualTo true
    }

    @ParameterizedTest
    @ValueSource(floats = [1.99f, 30.01f, 0f, -10f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY])
    fun `GIVEN an altitude outside the range or not a number WHEN checking it THEN is not valid`(metres: Float) {
        val result = rule.isValid(metres)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a default outside the range WHEN building the rule THEN it is refused`() {
        val build = { TakeOffAltitudeRule(defaultMetres = 31f) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a minimum on the ground WHEN building the rule THEN it is refused`() {
        val build = { TakeOffAltitudeRule(minMetres = 0f) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a minimum above the maximum WHEN building the rule THEN it is refused`() {
        val build = { TakeOffAltitudeRule(minMetres = 20f, maxMetres = 5f) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }
}
