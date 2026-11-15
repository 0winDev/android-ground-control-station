package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class LowVoltageTimerReadTest {

    private lateinit var timer: LowVoltageTimer.Companion

    @BeforeEach
    fun setUp() {
        timer = LowVoltageTimer
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 10])
    fun `GIVEN seconds above 0 WHEN reading the timer THEN is that timer`(seconds: Int) {
        val result = timer.of(seconds)

        result shouldBeEqualTo LowVoltageTimer.Seconds(seconds)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `GIVEN 0 or less WHEN reading the timer THEN is disabled`(seconds: Int) {
        val result = timer.of(seconds)

        result shouldBeEqualTo LowVoltageTimer.Disabled
    }

    @Test
    fun `GIVEN nothing read WHEN reading the timer THEN is unknown`() {
        val seconds: Int? = null

        val result = timer.of(seconds)

        result shouldBeEqualTo LowVoltageTimer.Unknown
    }
}
