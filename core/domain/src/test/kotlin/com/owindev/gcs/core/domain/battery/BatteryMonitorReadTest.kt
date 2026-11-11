package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
