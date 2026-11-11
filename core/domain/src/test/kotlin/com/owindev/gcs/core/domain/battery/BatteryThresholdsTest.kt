package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class BatteryThresholdsTest {

    private lateinit var thresholds: BatteryThresholds

    @BeforeEach
    fun setUp() {
        thresholds = BatteryThresholds(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
            low = ARMED_LEVEL,
            critical = ARMED_LEVEL,
            capacityMah = CAPACITY_MAH,
        )
    }

    @Test
    fun `GIVEN a voltage threshold and an action WHEN reading the status THEN is armed`() {
        val level = thresholds.low

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN only a capacity threshold and an action WHEN reading the status THEN is armed`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled, remainingMah = REMAINING_MAH)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a threshold and action 0 WHEN reading the status THEN only warns`() {
        val level = ARMED_LEVEL.copy(action = FailsafeAction.WarnOnly)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.WARN_ONLY
    }

    @Test
    fun `GIVEN both thresholds disabled WHEN reading the status THEN has no threshold whatever the action`() {
        val level = BatteryFailsafeLevel(BatteryThreshold.Disabled, BatteryThreshold.Disabled, FailsafeAction.Unknown)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.NO_THRESHOLD
    }

    @Test
    fun `GIVEN no monitor WHEN reading the status THEN has no monitor whatever the level holds`() {
        val withoutMonitor = thresholds.copy(monitor = BatteryMonitor.None)

        val result = withoutMonitor.statusOf(BatteryFailsafeLevel.UNKNOWN)

        result shouldBeEqualTo FailsafeStatus.NO_MONITOR
    }

    @Test
    fun `GIVEN the monitor unknown WHEN reading an armed level THEN is unknown`() {
        val unknownMonitor = thresholds.copy(monitor = BatteryMonitor.Unknown)

        val result = unknownMonitor.statusOf(ARMED_LEVEL)

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN only the voltage unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Unknown, remainingMah = REMAINING_MAH)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN only the capacity left unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(remainingMah = BatteryThreshold.Unknown)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the other threshold disabled and one unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled, remainingMah = BatteryThreshold.Unknown)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN a threshold and the action unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(action = FailsafeAction.Unknown)

        val result = thresholds.statusOf(level)

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN nothing read WHEN reading both levels THEN both are unknown`() {
        val unknown = BatteryThresholds.UNKNOWN

        val result = listOf(unknown.statusOf(unknown.low), unknown.statusOf(unknown.critical))

        result shouldBeEqualTo listOf(FailsafeStatus.UNKNOWN, FailsafeStatus.UNKNOWN)
    }

    private companion object {
        const val MONITOR_VOLTAGE_AND_CURRENT = 4
        const val CAPACITY_MAH = 3300
        const val ACTION_RTL = 2
        val REMAINING_MAH = BatteryThreshold.At(500f)
        val ARMED_LEVEL = BatteryFailsafeLevel(
            voltage = BatteryThreshold.At(10.5f),
            remainingMah = BatteryThreshold.Disabled,
            action = FailsafeAction.Acts(ACTION_RTL),
        )
    }
}
