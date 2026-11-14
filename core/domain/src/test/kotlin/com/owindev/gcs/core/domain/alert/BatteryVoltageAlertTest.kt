package com.owindev.gcs.core.domain.alert

import com.owindev.gcs.core.domain.battery.BatteryFailsafeLevel
import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeAction
import com.owindev.gcs.core.domain.battery.LowVoltageTimer
import com.owindev.gcs.core.domain.battery.SentVoltage
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** [AlertRule] on the voltage and at the vehicle's own thresholds (#53). */
@Tag("REQ-028")
class BatteryVoltageAlertTest {

    private val clock = TestTimeSource()
    private var raised = 0L
    private val evaluatingSince = clock.markNow() - SETTLED

    private lateinit var rule: AlertRule

    @BeforeEach
    fun setUp() {
        rule = AlertRule(timeSource = clock)
    }

    @Test
    fun `GIVEN a voltage below the low threshold WHEN it stays so for the timer THEN the voltage is low`() {
        val voltages = List(TIMER_SECONDS + 1) { BELOW_LOW }

        val result = alertsAfter(AlertState(), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN a voltage sag shorter than the timer WHEN it recovers THEN raises nothing`() {
        val voltages = List(TIMER_SECONDS) { BELOW_LOW } + AT_LOW

        val result = alertsAfter(AlertState(), voltages, VEHICLE)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN two sags each shorter than the timer WHEN together they last longer THEN raises nothing`() {
        val voltages = List(TIMER_SECONDS) { BELOW_LOW } + AT_LOW + List(2) { BELOW_LOW }

        val result = alertsAfter(AlertState(), voltages, VEHICLE)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN a battery not monitored WHEN its voltage stays below for the timer THEN no voltage alert`() {
        val voltages = List(TIMER_SECONDS + 1) { BELOW_LOW }

        val result = alertsAfter(AlertState(), voltages, VEHICLE, monitored = false)

        result shouldBeEqualTo setOf(Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN a voltage below the critical threshold WHEN it stays so for the timer THEN is critical only`() {
        val voltages = List(TIMER_SECONDS + 1) { BELOW_CRITICAL }

        val result = alertsAfter(AlertState(), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageCritical)
    }

    @Test
    fun `GIVEN a low voltage WHEN it stays at the threshold for the timer THEN clears`() {
        val voltages = List(TIMER_SECONDS + 1) { AT_LOW }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageLow), voltages, VEHICLE)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN a low voltage WHEN it stays at the threshold for less than the timer THEN stays low`() {
        val voltages = List(TIMER_SECONDS) { AT_LOW }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageLow), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN a critical voltage WHEN it comes back between the thresholds for the timer THEN is low`() {
        val voltages = List(TIMER_SECONDS + 1) { BELOW_LOW }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageCritical), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN a long critical voltage WHEN it is between the thresholds for the timer THEN is low at once`() {
        val voltages = List(2 * TIMER_SECONDS + 1) { BELOW_CRITICAL } + List(TIMER_SECONDS + 1) { BELOW_LOW }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageCritical), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN a voltage below for most of the timer WHEN a stale gap and one reading follow THEN no raise`() {
        val before = stateAfter(AlertState(), List(TIMER_SECONDS) { BELOW_LOW }, VEHICLE)
        val stale = evaluateStale(before, VEHICLE)

        val result = alertsAfter(stale, listOf(BELOW_LOW), VEHICLE)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN a low voltage back for most of the timer WHEN a stale gap and one reading follow THEN stays`() {
        val before = stateAfter(stateWith(Alert.BatteryVoltageLow), List(TIMER_SECONDS) { AT_LOW }, VEHICLE)
        val stale = evaluateStale(before, VEHICLE)

        val result = alertsAfter(stale, listOf(AT_LOW), VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN a voltage below for most of the timer WHEN the battery goes stale THEN the debounce starts again`() {
        val before = stateAfter(AlertState(), List(TIMER_SECONDS) { BELOW_LOW }, VEHICLE)

        val result = evaluateStale(before, VEHICLE)

        result.lowVoltageSince shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a low voltage WHEN 0 V arrives for longer than the timer THEN stays low and is unknown`() {
        val voltages = List(TIMER_SECONDS + 1) { NO_VOLTAGE }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageLow), voltages, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow, Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN a voltage below for most of the timer WHEN 0 V arrives THEN the debounce starts again`() {
        val before = stateAfter(AlertState(), List(TIMER_SECONDS) { BELOW_LOW }, VEHICLE)

        val result = stateAfter(before, listOf(NO_VOLTAGE), VEHICLE).lowVoltageSince

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a low voltage WHEN the vehicle disables its threshold THEN clears at once`() {
        val disabled = VEHICLE.copy(low = VEHICLE.low.copy(voltage = BatteryThreshold.Disabled))

        val result = alertsAfter(stateWith(Alert.BatteryVoltageLow), listOf(BELOW_LOW), disabled)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN a critical voltage WHEN BATT_MONITOR changes in flight to type 31 THEN stays, unknown`() {
        val currentOnly = VEHICLE.copy(monitor = BatteryMonitor.Present(MONITOR_ANALOG_CURRENT_ONLY))

        val result = alertsAfter(stateWith(Alert.BatteryVoltageCritical), listOf(BELOW_CRITICAL), currentOnly)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageCritical, Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN a critical voltage WHEN BATT_MONITOR changes in flight to 0 THEN stays, unknown`() {
        val noMonitor = VEHICLE.copy(monitor = BatteryMonitor.None)

        val result = alertsAfter(stateWith(Alert.BatteryVoltageCritical), listOf(BELOW_CRITICAL), noMonitor)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageCritical, Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN the resting voltage sent and the raw one compared WHEN the voltage stays below THEN it is not judged`() {
        val notComparable = VEHICLE.copy(sentVoltage = SentVoltage.MAY_BE_ABOVE_COMPARED)

        val result = alertsAfter(AlertState(), List(2 * TIMER_SECONDS + 1) { BELOW_CRITICAL }, notComparable)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageNotJudged, Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN the voltage not comparable and an energy WHEN evaluating THEN only says the voltage is not judged`() {
        val notComparable = VEHICLE.copy(sentVoltage = SentVoltage.MAY_BE_ABOVE_COMPARED)
        val telemetry = telemetryOf(
            Battery(voltage = BELOW_CRITICAL, current = 1.0, remaining = 50, monitored = true, healthy = true),
        )

        val result = rule.evaluate(
            AlertState(),
            AlertInput(telemetry, notComparable, ARMED, evaluatingSince),
            ::nextOccurrence,
        )

        result.active.map(ActiveAlert::alert) shouldBeEqualTo listOf(Alert.BatteryVoltageNotJudged)
    }

    @Test
    fun `GIVEN the thresholds not read WHEN the voltage stays below for twice the timer THEN raises nothing`() {
        val voltages = List(2 * TIMER_SECONDS + 1) { BELOW_CRITICAL }

        val result = alertsAfter(AlertState(), voltages, VOLTAGE_ONLY_UNREAD)

        result shouldBeEqualTo setOf(Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN a low voltage WHEN the thresholds become unknown and it stays above them THEN stays low`() {
        val voltages = List(2 * TIMER_SECONDS + 1) { AT_LOW }

        val result = alertsAfter(stateWith(Alert.BatteryVoltageLow), voltages, VOLTAGE_ONLY_UNREAD)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow, Alert.BatteryUnknown)
    }

    @Test
    fun `GIVEN a voltage under a threshold raised in flight WHEN it stays below the new one for the timer THEN low`() {
        val before = stateAfter(AlertState(), List(TIMER_SECONDS) { BELOW_LOW }, VEHICLE_LOWER)

        val result = alertsAfter(before, List(TIMER_SECONDS + 1) { BELOW_LOW }, VEHICLE)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN an unhealthy monitor in failsafe WHEN its voltage stays below the critical threshold THEN critical`() {
        val voltages = List(TIMER_SECONDS + 1) { BELOW_CRITICAL }

        val result = alertsAfter(AlertState(), voltages, VEHICLE, healthy = false)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageCritical, Alert.BatteryUnhealthy)
    }

    @Test
    fun `GIVEN a voltage-only monitor with its thresholds known WHEN its voltage is fine THEN the battery is known`() {
        val voltages = listOf(ABOVE_LOW)

        val result = alertsAfter(AlertState(), voltages, VEHICLE)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN the timer 0 WHEN the voltage stays below for its default of 10 s THEN the voltage is low`() {
        val timerDisabled = VEHICLE.copy(lowVoltageTimer = LowVoltageTimer.Disabled)

        val result = alertsAfter(AlertState(), List(DEFAULT_TIMER_SECONDS + 1) { BELOW_LOW }, timerDisabled)

        result shouldBeEqualTo setOf(Alert.BatteryVoltageLow)
    }

    @Test
    fun `GIVEN the vehicle's capacity threshold WHEN the energy reaches it THEN is low only below it`() {
        val energies = listOf(VEHICLE_LOW_PERCENT, VEHICLE_LOW_PERCENT - 1)

        val result = energies.map { remaining -> energyAlertsOf(AlertState(), remaining) }

        result shouldBeEqualTo listOf(emptySet(), setOf(Alert.BatteryLow(EnergyLimitSource.VEHICLE)))
    }

    @Test
    fun `GIVEN a low energy at the vehicle's threshold WHEN it comes back THEN clears only 3 points above it`() {
        val energies = listOf(VEHICLE_LOW_PERCENT + 2, VEHICLE_LOW_PERCENT + 3)
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))

        val result = energies.map { remaining -> energyAlertsOf(previous, remaining) }

        result shouldBeEqualTo listOf(setOf(Alert.BatteryLow(EnergyLimitSource.VEHICLE)), emptySet())
    }

    @Test
    fun `GIVEN a low energy at the vehicle's threshold WHEN its settings are unknown again THEN stays low`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))

        val result = energyAlertsOf(previous, VEHICLE_LOW_PERCENT - 5, BatteryThresholds.UNKNOWN)

        result shouldBeEqualTo setOf(Alert.BatteryLow(EnergyLimitSource.VEHICLE))
    }

    @Test
    fun `GIVEN a critical energy at the vehicle's threshold WHEN its settings are unknown again THEN stays critical`() {
        val previous = stateWith(Alert.BatteryCritical(EnergyLimitSource.VEHICLE))

        val result = energyAlertsOf(previous, VEHICLE_LOW_PERCENT - 5, BatteryThresholds.UNKNOWN)

        result shouldBeEqualTo setOf(Alert.BatteryCritical(EnergyLimitSource.VEHICLE))
    }

    @Test
    fun `GIVEN a low energy at the vehicle's threshold WHEN only BATT_LOW_MAH is unknown again THEN stays low`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))
        val lowUnread = VEHICLE.copy(low = VEHICLE.low.copy(remainingMah = BatteryThreshold.Unknown))

        val result = energyAlertsOf(previous, VEHICLE_LOW_PERCENT - 5, lowUnread)

        result shouldBeEqualTo setOf(Alert.BatteryLow(EnergyLimitSource.VEHICLE))
    }

    @Test
    fun `GIVEN a critical energy at the vehicle's threshold WHEN only BATT_CRT_MAH is unknown again THEN stays`() {
        val previous = stateWith(Alert.BatteryCritical(EnergyLimitSource.VEHICLE))
        val criticalUnread = VEHICLE.copy(critical = VEHICLE.critical.copy(remainingMah = BatteryThreshold.Unknown))

        val result = energyAlertsOf(previous, VEHICLE_LOW_PERCENT - 5, criticalUnread)

        result shouldBeEqualTo setOf(Alert.BatteryCritical(EnergyLimitSource.VEHICLE))
    }

    @Test
    fun `GIVEN a low energy held at the vehicle's unread threshold WHEN it reaches the GCS's critical THEN critical`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))

        val result = energyAlertsOf(previous, GCS_CRITICAL_PERCENT, BatteryThresholds.UNKNOWN)

        result shouldBeEqualTo setOf(Alert.BatteryCritical(EnergyLimitSource.GCS_UNREAD))
    }

    @Test
    fun `GIVEN a low energy held as BATT_CRT_MAH is unknown again WHEN it reaches the GCS's critical THEN critical`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))
        val criticalUnread = VEHICLE.copy(critical = VEHICLE.critical.copy(remainingMah = BatteryThreshold.Unknown))

        val result = energyAlertsOf(previous, GCS_CRITICAL_PERCENT, criticalUnread)

        result shouldBeEqualTo setOf(Alert.BatteryCritical(EnergyLimitSource.GCS_UNREAD))
    }

    @Test
    fun `GIVEN a low energy held at the vehicle's unread threshold WHEN low at the GCS's too THEN keeps it`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.VEHICLE))

        val result = energyAlertsOf(previous, GCS_LOW_PERCENT, BatteryThresholds.UNKNOWN)

        result shouldBeEqualTo setOf(Alert.BatteryLow(EnergyLimitSource.VEHICLE))
    }

    @Test
    fun `GIVEN battery settings not read WHEN a whole round of requests passes THEN says the limits are not read`() {
        val before = rule.evaluate(AlertState(), inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)
        clock += AlertRule.BATTERY_LIMITS_DUE

        val result = rule.evaluate(before, inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)

        result.active.map(ActiveAlert::alert) shouldBeEqualTo listOf(Alert.BatteryLimitsNotRead)
    }

    @Test
    fun `GIVEN battery settings not read WHEN 1 s is left to a whole round THEN says nothing yet`() {
        val before = rule.evaluate(AlertState(), inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)
        clock += AlertRule.BATTERY_LIMITS_DUE - 1.seconds

        val result = rule.evaluate(before, inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)

        result.active shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN only BATT_OPTIONS not read WHEN a whole round of requests passes THEN says the limits are not read`() {
        val optionsUnread = VEHICLE.copy(sentVoltage = SentVoltage.UNKNOWN)
        val before = rule.evaluate(AlertState(), inputOf(optionsUnread), ::nextOccurrence)
        clock += AlertRule.BATTERY_LIMITS_DUE

        val result = rule.evaluate(before, inputOf(optionsUnread), ::nextOccurrence)

        result.active.map(ActiveAlert::alert) shouldBeEqualTo listOf(Alert.BatteryLimitsNotRead)
    }

    @Test
    fun `GIVEN each limit setting alone not read WHEN a whole round passes THEN says the limits are not read`() {
        val unread = listOf(
            VEHICLE.copy(low = VEHICLE.low.copy(voltage = BatteryThreshold.Unknown)),
            VEHICLE.copy(low = VEHICLE.low.copy(remainingMah = BatteryThreshold.Unknown)),
            VEHICLE.copy(critical = VEHICLE.critical.copy(voltage = BatteryThreshold.Unknown)),
            VEHICLE.copy(critical = VEHICLE.critical.copy(remainingMah = BatteryThreshold.Unknown)),
            VEHICLE.copy(lowVoltageTimer = LowVoltageTimer.Unknown),
            VEHICLE.copy(capacityMah = null),
            VEHICLE.copy(sentVoltage = SentVoltage.UNKNOWN),
        )
        val since = clock.markNow() - AlertRule.BATTERY_LIMITS_DUE

        val result = unread.map { thresholds ->
            rule.limitsNotReadAfter(AlertState(limitsUnreadSince = since), inputOf(thresholds))
        }

        result shouldBeEqualTo List(unread.size) { true }
    }

    @Test
    fun `GIVEN the limits said not read WHEN the settings are read THEN clears`() {
        val before = rule.evaluate(AlertState(), inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)
        clock += AlertRule.BATTERY_LIMITS_DUE
        val notRead = rule.evaluate(before, inputOf(BatteryThresholds.UNKNOWN), ::nextOccurrence)

        val result = rule.evaluate(notRead, inputOf(VEHICLE), ::nextOccurrence)

        result shouldBeEqualTo AlertState()
    }

    @Test
    fun `GIVEN no monitor and nothing else read WHEN a whole round of requests passes THEN says nothing`() {
        val noMonitor = BatteryThresholds.UNKNOWN.copy(monitor = BatteryMonitor.None)
        val before = rule.evaluate(AlertState(), inputOf(noMonitor), ::nextOccurrence)
        clock += AlertRule.BATTERY_LIMITS_DUE

        val result = rule.evaluate(before, inputOf(noMonitor), ::nextOccurrence)

        result.active shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN battery settings not read for 30 s WHEN asking when to evaluate again THEN is when they are due`() {
        val state = AlertState(limitsUnreadSince = clock.markNow())
        clock += 30.seconds

        val result = rule.untilNextEvaluation(state, inputOf(BatteryThresholds.UNKNOWN, receivedLater = true))

        result shouldBeEqualTo AlertRule.BATTERY_LIMITS_DUE - 30.seconds
    }

    @Test
    fun `GIVEN a low energy at the GCS's unread threshold WHEN it comes back THEN clears as before`() {
        val previous = stateWith(Alert.BatteryLow(EnergyLimitSource.GCS_UNREAD))

        val result = energyAlertsOf(previous, GCS_LOW_PERCENT + 3, BatteryThresholds.UNKNOWN)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN the vehicle sets no capacity threshold WHEN the energy is at the GCS's THEN low by the GCS's unset`() {
        val unset = VEHICLE.copy(low = VEHICLE.low.copy(remainingMah = BatteryThreshold.Disabled))

        val result = energyAlertsOf(AlertState(), GCS_LOW_PERCENT, unset)

        result shouldBeEqualTo setOf(Alert.BatteryLow(EnergyLimitSource.GCS_UNSET))
    }

    @Test
    fun `GIVEN a voltage below since 9 s WHEN asking when to evaluate again THEN is when the timer ends`() {
        val since = clock.markNow()
        clock += (TIMER_SECONDS - 1).seconds
        val state = AlertState(lowVoltageSince = since)

        val result = rule.untilNextEvaluation(
            state,
            AlertInput(telemetryAt(BELOW_LOW), VEHICLE, ARMED, evaluatingSince),
        )

        result shouldBeEqualTo 1.seconds
    }

    @Test
    fun `GIVEN only the critical threshold set and below since 9 s WHEN asking when to evaluate again THEN in 1 s`() {
        val since = clock.markNow()
        clock += (TIMER_SECONDS - 1).seconds
        val criticalOnly = VEHICLE.copy(low = VEHICLE.low.copy(voltage = BatteryThreshold.Disabled))
        val state = AlertState(criticalVoltageSince = since)

        val result = rule.untilNextEvaluation(
            state,
            AlertInput(telemetryAt(BELOW_CRITICAL), criticalOnly, ARMED, evaluatingSince),
        )

        result shouldBeEqualTo 1.seconds
    }

    /** The battery alerts, stale streams left out, after a reading of each of [voltages] 1 s apart from [previous]. */
    private fun alertsAfter(
        previous: AlertState,
        voltages: List<Double>,
        thresholds: BatteryThresholds,
        healthy: Boolean = true,
        monitored: Boolean = true,
    ): Set<Alert> = stateAfter(previous, voltages, thresholds, healthy, monitored).active
        .map(ActiveAlert::alert)
        .filterNotTo(mutableSetOf()) { it is Alert.TelemetryStale }

    private fun stateAfter(
        previous: AlertState,
        voltages: List<Double>,
        thresholds: BatteryThresholds,
        healthy: Boolean = true,
        monitored: Boolean = true,
    ): AlertState = voltages.foldIndexed(previous) { index, state, voltage ->
        if (index > 0) clock += READING_PERIOD
        val telemetry = telemetryAt(voltage, healthy, monitored)
        rule.evaluate(state, AlertInput(telemetry, thresholds, ARMED, evaluatingSince), ::nextOccurrence)
    }

    /** Evaluated once the last reading has gone stale, with nothing new. */
    private fun evaluateStale(previous: AlertState, thresholds: BatteryThresholds): AlertState {
        val lastReading = telemetryAt(BELOW_LOW)
        clock += STALE_GAP
        return rule.evaluate(previous, AlertInput(lastReading, thresholds, ARMED, evaluatingSince), ::nextOccurrence)
    }

    private fun energyAlertsOf(
        previous: AlertState,
        remaining: Int,
        thresholds: BatteryThresholds = VEHICLE,
    ): Set<Alert> {
        val battery =
            Battery(voltage = ABOVE_LOW, current = 1.0, remaining = remaining, monitored = true, healthy = true)
        val telemetry = telemetryOf(battery)
        return rule.evaluate(previous, AlertInput(telemetry, thresholds, ARMED, evaluatingSince), ::nextOccurrence)
            .active.map(ActiveAlert::alert).filterTo(mutableSetOf()) {
                it is Alert.BatteryLow ||
                    it is Alert.BatteryCritical
            }
    }

    private fun stateWith(alert: Alert): AlertState = AlertState(
        active = listOf(ActiveAlert(alert, occurrence = 0, raisedAt = clock.markNow(), origin = DataOrigin.LIVE)),
    )

    private fun telemetryAt(voltage: Double, healthy: Boolean = true, monitored: Boolean = true): Telemetry =
        telemetryOf(
            Battery(voltage = voltage, current = null, remaining = null, monitored = monitored, healthy = healthy),
        )

    private fun telemetryOf(battery: Battery, receivedAt: TimeMark = clock.markNow()) = Telemetry(
        battery = Timed(battery, receivedAt),
        gps = Timed(FIX_3D, receivedAt),
        origin = DataOrigin.LIVE,
    )

    private fun AlertRule.limitsNotReadAfter(state: AlertState, input: AlertInput): Boolean =
        Alert.BatteryLimitsNotRead in evaluate(state, input, ::nextOccurrence).active.map(ActiveAlert::alert)

    /** A healthy battery at 50 % and 11.1 V, fresh, or fresh for a long while if [receivedLater]. */
    private fun inputOf(thresholds: BatteryThresholds, receivedLater: Boolean = false): AlertInput {
        val battery = Battery(voltage = ABOVE_LOW, current = 1.0, remaining = 50, monitored = true, healthy = true)
        val receivedAt = if (receivedLater) clock.markNow() + SETTLED else clock.markNow()
        return AlertInput(telemetryOf(battery, receivedAt), thresholds, ARMED, evaluatingSince)
    }

    private fun nextOccurrence(): Long = ++raised

    private companion object {
        const val ARMED = true
        const val TIMER_SECONDS = 10
        const val DEFAULT_TIMER_SECONDS = 10
        const val LOW_VOLTS = 10.5f
        const val CRITICAL_VOLTS = 9.9f
        const val LOWER_LOW_VOLTS = 10.0f
        const val ABOVE_LOW = 11.1
        const val AT_LOW = 10.5
        const val BELOW_LOW = 10.4
        const val BELOW_CRITICAL = 9.8
        const val NO_VOLTAGE = 0.0
        const val CAPACITY_MAH = 3300
        const val VEHICLE_LOW_PERCENT = 30
        const val VEHICLE_LOW_MAH = 990f
        const val GCS_LOW_PERCENT = 20
        const val GCS_CRITICAL_PERCENT = 10
        const val MONITOR_VOLTAGE_AND_CURRENT = 4
        const val MONITOR_ANALOG_CURRENT_ONLY = 31
        val READING_PERIOD: Duration = 1.seconds
        val STALE_GAP: Duration = 4.seconds
        val SETTLED: Duration = 1.minutes
        val FIX_3D = GpsStatus(
            fixType = GpsFixType.Fix3d,
            satellitesVisible = 10,
            hdop = 1.21,
            vdop = 2.0,
            groundSpeed = 0.0,
            courseOverGround = 0.0,
            horizontalAccuracy = 0.3,
            verticalAccuracy = 0.3,
            yaw = null,
        )

        /** ArduCopter's defaults for the low level (`BATT_LOW_VOLT` 10.5, timer 10 s) and a critical one at 9.9 V. */
        val VEHICLE = BatteryThresholds(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
            low = BatteryFailsafeLevel(
                BatteryThreshold.At(LOW_VOLTS),
                BatteryThreshold.At(VEHICLE_LOW_MAH),
                FailsafeAction.WarnOnly,
            ),
            critical = BatteryFailsafeLevel(
                BatteryThreshold.At(CRITICAL_VOLTS),
                BatteryThreshold.Disabled,
                FailsafeAction.WarnOnly,
            ),
            lowVoltageTimer = LowVoltageTimer.Seconds(TIMER_SECONDS),
            capacityMah = CAPACITY_MAH,
            sentVoltage = SentVoltage.NEVER_ABOVE_COMPARED,
        )

        /** The same with `BATT_LOW_VOLT` lowered to 10 V, so 10.4 V is above it. */
        val VEHICLE_LOWER = VEHICLE.copy(low = VEHICLE.low.copy(voltage = BatteryThreshold.At(LOWER_LOW_VOLTS)))

        val VOLTAGE_ONLY_UNREAD = BatteryThresholds.UNKNOWN
    }
}
