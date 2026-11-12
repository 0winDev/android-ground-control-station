package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class BatteryThresholdReadTest {

    private lateinit var threshold: BatteryThreshold.Companion

    @BeforeEach
    fun setUp() {
        threshold = BatteryThreshold
    }

    @Test
    fun `GIVEN a value above 0 WHEN reading a threshold THEN is that threshold`() {
        val value = 10.5f

        val result = threshold.of(value)

        result shouldBeEqualTo BatteryThreshold.At(value)
    }

    @ParameterizedTest
    @ValueSource(floats = [0f, -0f, -1f])
    fun `GIVEN 0 or less WHEN reading a threshold THEN is disabled`(value: Float) {
        val read = value

        val result = threshold.of(read)

        result shouldBeEqualTo BatteryThreshold.Disabled
    }

    @ParameterizedTest
    @ValueSource(floats = [Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY])
    fun `GIVEN a value that is not finite WHEN reading a threshold THEN is unknown`(value: Float) {
        val read = value

        val result = threshold.of(read)

        result shouldBeEqualTo BatteryThreshold.Unknown
    }

    @Test
    fun `GIVEN nothing read WHEN reading a threshold THEN is unknown`() {
        val value: Float? = null

        val result = threshold.of(value)

        result shouldBeEqualTo BatteryThreshold.Unknown
    }
}
