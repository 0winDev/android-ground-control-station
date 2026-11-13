package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class BatteryMonitorReadTest {

    private lateinit var monitor: BatteryMonitor.Companion

    @BeforeEach
    fun setUp() {
        monitor = BatteryMonitor
    }

    @Test
    fun `GIVEN a monitor type WHEN reading the monitor THEN is present with that type`() {
        val type = 4

        val result = monitor.of(type)

        result shouldBeEqualTo BatteryMonitor.Present(type)
    }

    @ParameterizedTest
    @ValueSource(ints = [4, 7, 8, 21, 31])
    fun `GIVEN a type whose backend measures current WHEN reading the monitor THEN it measures current`(type: Int) {
        val result = (monitor.of(type) as? BatteryMonitor.Present)?.measuresCurrent

        result shouldBeEqualTo true
    }

    @ParameterizedTest
    @ValueSource(ints = [3, 5, 9, 10, 25, 32])
    fun `GIVEN any other type WHEN reading the monitor THEN it is not known to measure current`(type: Int) {
        val result = (monitor.of(type) as? BatteryMonitor.Present)?.measuresCurrent

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN 0 WHEN reading the monitor THEN there is none`() {
        val type = 0

        val result = monitor.of(type)

        result shouldBeEqualTo BatteryMonitor.None
    }

    @Test
    fun `GIVEN nothing read WHEN reading the monitor THEN is unknown`() {
        val type: Int? = null

        val result = monitor.of(type)

        result shouldBeEqualTo BatteryMonitor.Unknown
    }
}
