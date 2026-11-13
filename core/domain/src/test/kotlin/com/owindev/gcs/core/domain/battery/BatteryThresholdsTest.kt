package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class BatteryThresholdsTest {

    private lateinit var thresholds: BatteryThresholds

    @BeforeEach
    fun setUp() {
        thresholds = BatteryThresholds(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
            low = ARMED_LEVEL,
            critical = ARMED_LEVEL,
            lowVoltageTimer = LowVoltageTimer.Seconds(LOW_TIMER_SECONDS),
            capacityMah = CAPACITY_MAH,
        )
    }

    @Test
    fun `GIVEN a voltage threshold and an action WHEN reading the status THEN is armed`() {
        val level = thresholds.low

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN only a capacity threshold and an action WHEN reading the status THEN is armed`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled, remainingMah = REMAINING_MAH)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a voltage threshold and the low voltage timer 0 WHEN reading the status THEN has no threshold`() {
        val timerDisabled = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Disabled)

        val result = timerDisabled.copy(low = ARMED_LEVEL).lowStatus

        result shouldBeEqualTo FailsafeStatus.NO_THRESHOLD
    }

    @Test
    fun `GIVEN the low voltage timer 0 and a capacity threshold WHEN reading the status THEN is armed`() {
        val timerDisabled = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Disabled)

        val result = timerDisabled.copy(low = ARMED_LEVEL.copy(remainingMah = REMAINING_MAH)).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a voltage threshold and the low voltage timer unknown WHEN reading the status THEN is unknown`() {
        val timerUnknown = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Unknown)

        val result = timerUnknown.copy(low = ARMED_LEVEL).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the voltage disabled and the low voltage timer unknown WHEN reading the status THEN has no threshold`() {
        val timerUnknown = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Unknown)

        val result = timerUnknown.copy(low = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled)).lowStatus

        result shouldBeEqualTo FailsafeStatus.NO_THRESHOLD
    }

    @Test
    fun `GIVEN only a capacity threshold on a monitor not known to measure current WHEN reading THEN is unknown`() {
        val voltageOnly = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_ONLY))
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled, remainingMah = REMAINING_MAH)

        val result = voltageOnly.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the capacity disabled on a monitor not known to measure current WHEN reading THEN is armed`() {
        val voltageOnly = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_ONLY))

        val result = voltageOnly.copy(low = ARMED_LEVEL).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN only a voltage threshold on an analog current only monitor WHEN reading THEN has no threshold`() {
        val currentOnly = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_CURRENT_ONLY))

        val result = currentOnly.copy(low = ARMED_LEVEL).lowStatus

        result shouldBeEqualTo FailsafeStatus.NO_THRESHOLD
    }

    @Test
    fun `GIVEN both thresholds on an analog current only monitor WHEN reading the status THEN is armed`() {
        val currentOnly = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_CURRENT_ONLY))

        val result = currentOnly.copy(low = ARMED_LEVEL.copy(remainingMah = REMAINING_MAH)).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a voltage armed and a capacity on a monitor not known to measure current WHEN read THEN is unknown`() {
        val voltageOnly = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_ONLY))

        val result = voltageOnly.copy(low = ARMED_LEVEL.copy(remainingMah = REMAINING_MAH)).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the timer 0 and a capacity on a monitor not known to measure current WHEN reading THEN is unknown`() {
        val timerDisabled = thresholds.copy(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_ONLY),
            lowVoltageTimer = LowVoltageTimer.Disabled,
        )

        val result = timerDisabled.copy(low = ARMED_LEVEL.copy(remainingMah = REMAINING_MAH)).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the timer unknown and a capacity threshold WHEN reading the status THEN is unknown`() {
        val timerUnknown = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Unknown)

        val result = timerUnknown.copy(low = ARMED_LEVEL.copy(remainingMah = REMAINING_MAH)).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @ParameterizedTest
    @ValueSource(ints = [6, 7])
    fun `GIVEN an unranked action and the other level able to fire WHEN reading the status THEN is unknown`(code: Int) {
        val otherLevelWarnsOnly = thresholds.copy(critical = ARMED_LEVEL.copy(action = FailsafeAction.WarnOnly))

        val result = otherLevelWarnsOnly.copy(low = ARMED_LEVEL.copy(action = FailsafeAction.Acts(code))).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN an unranked action and the other level unknown WHEN reading the status THEN is unknown`() {
        val otherLevelUnknown = thresholds.copy(critical = BatteryFailsafeLevel.UNKNOWN)

        val result = otherLevelUnknown.copy(low = ARMED_LEVEL.copy(action = BRAKE_OR_LAND)).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN an unranked action and the other level without threshold WHEN reading the status THEN is armed`() {
        val otherLevelOff = thresholds.copy(critical = NO_THRESHOLD_LEVEL)

        val result = otherLevelOff.copy(low = ARMED_LEVEL.copy(action = BRAKE_OR_LAND)).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `GIVEN a ranked action and the other level able to fire WHEN reading the status THEN is armed`(code: Int) {
        val otherLevelWarnsOnly = thresholds.copy(critical = ARMED_LEVEL.copy(action = FailsafeAction.WarnOnly))

        val result = otherLevelWarnsOnly.copy(low = ARMED_LEVEL.copy(action = FailsafeAction.Acts(code))).lowStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a critical unranked action and the low level without threshold WHEN reading critical THEN is armed`() {
        val lowOff = thresholds.copy(low = NO_THRESHOLD_LEVEL, critical = ARMED_LEVEL.copy(action = BRAKE_OR_LAND))

        val result = lowOff.criticalStatus

        result shouldBeEqualTo FailsafeStatus.ARMED
    }

    @Test
    fun `GIVEN a critical unranked action and the low level able to fire WHEN reading the critical THEN is unknown`() {
        val lowWarnsOnly = thresholds.copy(
            low = ARMED_LEVEL.copy(action = FailsafeAction.WarnOnly),
            critical = ARMED_LEVEL.copy(action = BRAKE_OR_LAND),
        )

        val result = lowWarnsOnly.criticalStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN a threshold and action 0 WHEN reading the status THEN only warns`() {
        val level = ARMED_LEVEL.copy(action = FailsafeAction.WarnOnly)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.WARN_ONLY
    }

    @Test
    fun `GIVEN both thresholds disabled WHEN reading the status THEN has no threshold whatever the action`() {
        val level = BatteryFailsafeLevel(BatteryThreshold.Disabled, BatteryThreshold.Disabled, FailsafeAction.Unknown)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.NO_THRESHOLD
    }

    @Test
    fun `GIVEN no monitor WHEN reading the status THEN has no monitor whatever the level holds`() {
        val withoutMonitor = thresholds.copy(monitor = BatteryMonitor.None)

        val result = withoutMonitor.copy(low = BatteryFailsafeLevel.UNKNOWN).lowStatus

        result shouldBeEqualTo FailsafeStatus.NO_MONITOR
    }

    @Test
    fun `GIVEN the monitor unknown WHEN reading an armed level THEN is unknown`() {
        val unknownMonitor = thresholds.copy(monitor = BatteryMonitor.Unknown)

        val result = unknownMonitor.copy(low = ARMED_LEVEL).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN only the voltage unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Unknown, remainingMah = REMAINING_MAH)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN only the capacity left unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(remainingMah = BatteryThreshold.Unknown)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN the other threshold disabled and one unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(voltage = BatteryThreshold.Disabled, remainingMah = BatteryThreshold.Unknown)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN a threshold and the action unknown WHEN reading the status THEN is unknown`() {
        val level = ARMED_LEVEL.copy(action = FailsafeAction.Unknown)

        val result = thresholds.copy(low = level).lowStatus

        result shouldBeEqualTo FailsafeStatus.UNKNOWN
    }

    @Test
    fun `GIVEN nothing read WHEN reading both levels THEN both are unknown`() {
        val unknown = BatteryThresholds.UNKNOWN

        val result = listOf(unknown.lowStatus, unknown.criticalStatus)

        result shouldBeEqualTo listOf(FailsafeStatus.UNKNOWN, FailsafeStatus.UNKNOWN)
    }

    private companion object {
        const val MONITOR_VOLTAGE_AND_CURRENT = 4
        const val MONITOR_VOLTAGE_ONLY = 3
        const val MONITOR_CURRENT_ONLY = 31
        const val LOW_TIMER_SECONDS = 10
        const val CAPACITY_MAH = 3300
        const val ACTION_RTL = 2
        val REMAINING_MAH = BatteryThreshold.At(500f)
        val BRAKE_OR_LAND = FailsafeAction.Acts(7)
        val NO_THRESHOLD_LEVEL = BatteryFailsafeLevel(
            voltage = BatteryThreshold.Disabled,
            remainingMah = BatteryThreshold.Disabled,
            action = FailsafeAction.WarnOnly,
        )
        val ARMED_LEVEL = BatteryFailsafeLevel(
            voltage = BatteryThreshold.At(10.5f),
            remainingMah = BatteryThreshold.Disabled,
            action = FailsafeAction.Acts(ACTION_RTL),
        )
    }
}
