package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.battery.BatteryFailsafeLevel
import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeAction
import com.owindev.gcs.core.domain.battery.LowVoltageTimer
import com.owindev.gcs.core.domain.battery.SentVoltage
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class BatteryAlertLimitsTest {

    private val defaults = GcsEnergyThresholds()

    private lateinit var thresholds: BatteryThresholds

    @BeforeEach
    fun setUp() {
        thresholds = BatteryThresholds(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
            low = BatteryFailsafeLevel(
                BatteryThreshold.At(LOW_VOLTS),
                BatteryThreshold.At(LOW_MAH),
                FailsafeAction.Acts(ACTION_RTL),
            ),
            critical = BatteryFailsafeLevel(
                BatteryThreshold.At(CRITICAL_VOLTS),
                BatteryThreshold.At(CRITICAL_MAH),
                FailsafeAction.Acts(ACTION_LAND),
            ),
            lowVoltageTimer = LowVoltageTimer.Seconds(TIMER_SECONDS),
            capacityMah = CAPACITY_MAH,
            sentVoltage = SentVoltage.NEVER_ABOVE_COMPARED,
        )
    }

    @Test
    fun `GIVEN every setting read WHEN deriving the limits THEN they are the vehicle's`() {
        val read = thresholds

        val result = batteryAlertLimitsOf(read, defaults)

        result shouldBeEqualTo BatteryAlertLimits(
            low = BatteryLevelLimits(
                VoltageLimit.Below(LOW_VOLTS, TIMER_SECONDS.seconds),
                EnergyLimit(LOW_PERCENT, EnergyLimitSource.VEHICLE),
            ),
            critical = BatteryLevelLimits(
                VoltageLimit.Below(CRITICAL_VOLTS, TIMER_SECONDS.seconds),
                EnergyLimit(CRITICAL_PERCENT, EnergyLimitSource.VEHICLE),
            ),
        )
    }

    @Test
    fun `GIVEN nothing read WHEN deriving the limits THEN voltage is unknown and energy the GCS's unread`() {
        val read = BatteryThresholds.UNKNOWN

        val result = batteryAlertLimitsOf(read, defaults)

        result shouldBeEqualTo BatteryAlertLimits(
            low = BatteryLevelLimits(
                VoltageLimit.Unknown,
                EnergyLimit(defaults.low.toDouble(), EnergyLimitSource.GCS_UNREAD),
            ),
            critical = BatteryLevelLimits(
                VoltageLimit.Unknown,
                EnergyLimit(defaults.critical.toDouble(), EnergyLimitSource.GCS_UNREAD),
            ),
        )
    }

    @Test
    fun `GIVEN the monitor not read WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(monitor = BatteryMonitor.Unknown)

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN no monitor, which takes effect on a reboot WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(monitor = BatteryMonitor.None)

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN a monitor that reads no voltage WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(monitor = BatteryMonitor.Present(MONITOR_ANALOG_CURRENT_ONLY))

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN the voltage threshold not read WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(low = thresholds.low.copy(voltage = BatteryThreshold.Unknown))

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN the voltage threshold disabled and the timer not read WHEN deriving the limits THEN it is off`() {
        val read = thresholds.copy(
            low = thresholds.low.copy(voltage = BatteryThreshold.Disabled),
            lowVoltageTimer = LowVoltageTimer.Unknown,
        )

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Off
    }

    @Test
    fun `GIVEN the timer not read WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Unknown)

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN the timer 0 WHEN deriving the limits THEN both voltage ones are debounced by its default of 10 s`() {
        val read = thresholds.copy(lowVoltageTimer = LowVoltageTimer.Disabled)

        val result = batteryAlertLimitsOf(read, defaults).let { listOf(it.low.voltage, it.critical.voltage) }

        result shouldBeEqualTo listOf(
            VoltageLimit.Below(LOW_VOLTS, DEFAULT_TIMER_SECONDS.seconds),
            VoltageLimit.Below(CRITICAL_VOLTS, DEFAULT_TIMER_SECONDS.seconds),
        )
    }

    @Test
    fun `GIVEN the voltage sent may be above the one compared WHEN deriving the limits THEN both are not comparable`() {
        val read = thresholds.copy(sentVoltage = SentVoltage.MAY_BE_ABOVE_COMPARED)

        val result = batteryAlertLimitsOf(read, defaults).let { listOf(it.low.voltage, it.critical.voltage) }

        result shouldBeEqualTo listOf(VoltageLimit.NotComparable, VoltageLimit.NotComparable)
    }

    @Test
    fun `GIVEN the voltage sent may be above and the threshold disabled WHEN deriving the limits THEN it is off`() {
        val read = thresholds.copy(
            sentVoltage = SentVoltage.MAY_BE_ABOVE_COMPARED,
            low = thresholds.low.copy(voltage = BatteryThreshold.Disabled),
        )

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Off
    }

    @Test
    fun `GIVEN which voltage is sent not read WHEN deriving the limits THEN the voltage one is unknown`() {
        val read = thresholds.copy(sentVoltage = SentVoltage.UNKNOWN)

        val result = batteryAlertLimitsOf(read, defaults).low.voltage

        result shouldBeEqualTo VoltageLimit.Unknown
    }

    @Test
    fun `GIVEN the capacity thresholds disabled WHEN deriving the limits THEN energy is the GCS's unset`() {
        val read = thresholds.copy(
            low = thresholds.low.copy(remainingMah = BatteryThreshold.Disabled),
            critical = thresholds.critical.copy(remainingMah = BatteryThreshold.Disabled),
            capacityMah = null,
        )

        val result = batteryAlertLimitsOf(read, defaults).let { listOf(it.low.energy, it.critical.energy) }

        result shouldBeEqualTo listOf(
            EnergyLimit(defaults.low.toDouble(), EnergyLimitSource.GCS_UNSET),
            EnergyLimit(defaults.critical.toDouble(), EnergyLimitSource.GCS_UNSET),
        )
    }

    @Test
    fun `GIVEN a capacity threshold not read WHEN deriving the limits THEN energy is the GCS's unread`() {
        val read = thresholds.copy(low = thresholds.low.copy(remainingMah = BatteryThreshold.Unknown))

        val result = batteryAlertLimitsOf(read, defaults).low.energy

        result shouldBeEqualTo EnergyLimit(defaults.low.toDouble(), EnergyLimitSource.GCS_UNREAD)
    }

    @Test
    fun `GIVEN the capacity not read WHEN deriving the limits THEN energy is the GCS's unread`() {
        val read = thresholds.copy(capacityMah = null)

        val result = batteryAlertLimitsOf(read, defaults).low.energy

        result shouldBeEqualTo EnergyLimit(defaults.low.toDouble(), EnergyLimitSource.GCS_UNREAD)
    }

    @Test
    fun `GIVEN a capacity of 10 mAh WHEN deriving the limits THEN energy is the GCS's unset`() {
        val read = thresholds.copy(capacityMah = MIN_CAPACITY_MAH)

        val result = batteryAlertLimitsOf(read, defaults).low.energy

        result shouldBeEqualTo EnergyLimit(defaults.low.toDouble(), EnergyLimitSource.GCS_UNSET)
    }

    @Test
    fun `GIVEN a capacity of 11 mAh WHEN deriving the limits THEN energy is the vehicle's`() {
        val read = thresholds.copy(
            low = thresholds.low.copy(remainingMah = BatteryThreshold.At(SMALL_PACK_LOW_MAH)),
            capacityMah = MIN_CAPACITY_MAH + 1,
        )

        val result = batteryAlertLimitsOf(read, defaults).low.energy

        result shouldBeEqualTo EnergyLimit(SMALL_PACK_LOW_PERCENT, EnergyLimitSource.VEHICLE)
    }

    private companion object {
        const val MONITOR_VOLTAGE_AND_CURRENT = 4
        const val MONITOR_ANALOG_CURRENT_ONLY = 31
        const val ACTION_LAND = 1
        const val ACTION_RTL = 2
        const val LOW_VOLTS = 10.5f
        const val CRITICAL_VOLTS = 9.9f
        const val CAPACITY_MAH = 3300
        const val LOW_MAH = 990f
        const val LOW_PERCENT = 30.0
        const val CRITICAL_MAH = 495f
        const val CRITICAL_PERCENT = 15.0
        const val TIMER_SECONDS = 5
        const val DEFAULT_TIMER_SECONDS = 10
        const val MIN_CAPACITY_MAH = 10
        const val SMALL_PACK_LOW_MAH = 5.5f
        const val SMALL_PACK_LOW_PERCENT = 50.0
    }
}
