package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.telemetry.Battery
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-029")
class BatteryChipStateTest {

    @Test
    fun `GIVEN no battery received WHEN choosing the chip THEN has no reading`() {
        val battery: Battery? = null

        val result = batteryChipStateOf(battery)

        result shouldBeEqualTo BatteryChipState.NoReading
    }

    @Test
    fun `GIVEN a vehicle without a battery monitor WHEN choosing the chip THEN says so, never a reading`() {
        val battery = Battery(voltage = null, current = null, remaining = null, monitored = false, healthy = true)

        val result = batteryChipStateOf(battery)

        result shouldBeEqualTo BatteryChipState.NoMonitor
    }

    @Test
    fun `GIVEN a battery failsafe WHEN choosing the chip THEN shows the readings marked unhealthy`() {
        val battery = Battery(voltage = 12.6, current = 28.1, remaining = 54, monitored = true, healthy = false)

        val result = batteryChipStateOf(battery)

        result shouldBeEqualTo BatteryChipState.Measured(remaining = 54, voltage = 12.6, healthy = false)
    }

    @Test
    fun `GIVEN a healthy monitor WHEN choosing the chip THEN shows its readings`() {
        val battery = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

        val result = batteryChipStateOf(battery)

        result shouldBeEqualTo BatteryChipState.Measured(remaining = 100, voltage = 12.6, healthy = true)
    }
}
