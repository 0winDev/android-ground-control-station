package com.owindev.gcs.core.domain.preflight

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.alert.GcsEnergyThresholds
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import com.owindev.gcs.core.domain.battery.BatteryFailsafeLevel
import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeAction
import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.battery.LowVoltageTimer
import com.owindev.gcs.core.domain.battery.SentVoltage
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRule
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.preflight.PreflightColour.AMBER
import com.owindev.gcs.core.domain.preflight.PreflightColour.GREEN
import com.owindev.gcs.core.domain.preflight.PreflightColour.RED
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.PreArmStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Every case is [ALL_GREEN] with one condition changed, so each red, amber or green comes from that one alone. */
@Tag("REQ-048")
class PreflightRuleTest {

    private lateinit var rule: PreflightRule

    @BeforeEach
    fun setUp() {
        rule = PreflightRule(
            staleness = StalenessRule(),
            preArm = PreArmRule(),
            uplinkRule = UplinkRule(),
            backgroundRule = BackgroundLinkRule(LinkLossRule(), RecordingRule()),
            energyDefaults = GcsEnergyThresholds(),
        )
    }

    @Test
    fun `GIVEN everything fresh, read and confirmed WHEN checking THEN every item is green`() {
        val input = ALL_GREEN

        val result = rule.check(input)

        result.items shouldBeEqualTo PreflightKind.entries.map { PreflightItem(it, GREEN, PreflightReason.Ok) }
        result.reds.shouldBeEmpty()
        result.ambers.shouldBeEmpty()
    }

    @Test
    fun `GIVEN no active vehicle WHEN checking THEN the link is red`() {
        val input = ALL_GREEN.copy(vehicle = null)

        val result = rule.check(input)

        result[PreflightKind.LINK] shouldBeEqualTo red(PreflightKind.LINK, PreflightReason.NoActiveVehicle)
    }

    @Test
    fun `GIVEN a replayed vehicle WHEN checking THEN the link is red`() {
        val input = ALL_GREEN.copy(vehicle = VEHICLE.copy(origin = DataOrigin.REPLAY))

        val result = rule.check(input)

        result[PreflightKind.LINK] shouldBeEqualTo red(PreflightKind.LINK, PreflightReason.Replay)
    }

    @Test
    fun `GIVEN a vehicle that is LOST WHEN checking THEN the link is red`() {
        val input = ALL_GREEN.copy(vehicle = VEHICLE.copy(link = VehicleLink.LOST))

        val result = rule.check(input)

        result[PreflightKind.LINK] shouldBeEqualTo red(PreflightKind.LINK, PreflightReason.LinkLost)
    }

    @Test
    fun `GIVEN no answer since CONNECTED and nothing against the uplink WHEN checking THEN the uplink is red`() {
        val input = ALL_GREEN.copy(uplink = UplinkHealth.HEALTHY)

        val result = rule.check(input)

        result[PreflightKind.UPLINK] shouldBeEqualTo red(PreflightKind.UPLINK, PreflightReason.NotAnsweredYet)
    }

    @Test
    fun `GIVEN an answer and then three failed HEARTBEAT periods WHEN checking THEN the uplink is red`() {
        val input = ALL_GREEN.copy(
            uplink = ANSWERED.afterHeartbeatFailed().afterHeartbeatFailed().afterHeartbeatFailed(),
        )

        val result = rule.check(input)

        result[PreflightKind.UPLINK] shouldBeEqualTo red(PreflightKind.UPLINK, PreflightReason.UplinkSendsFailing)
    }

    @Test
    fun `GIVEN an answer and then a command without answer WHEN checking THEN the uplink is red`() {
        val input = ALL_GREEN.copy(uplink = ANSWERED.afterCommandUnanswered(NOW))

        val result = rule.check(input)

        result[PreflightKind.UPLINK] shouldBeEqualTo
            red(PreflightKind.UPLINK, PreflightReason.UplinkCommandUnanswered)
    }

    @Test
    fun `GIVEN the background link not running WHEN checking THEN it is red`() {
        val input = ALL_GREEN.copy(background = BackgroundLinkStatus(running = false))

        val result = rule.check(input)

        result[PreflightKind.BACKGROUND_LINK] shouldBeEqualTo
            red(PreflightKind.BACKGROUND_LINK, PreflightReason.BackgroundLinkNotRunning)
    }

    @Test
    fun `GIVEN the background link running but restricted WHEN checking THEN it is amber`() {
        val input = ALL_GREEN.copy(background = RUNNING.copy(backgroundRestricted = true))

        val result = rule.check(input)

        result[PreflightKind.BACKGROUND_LINK] shouldBeEqualTo
            PreflightItem(PreflightKind.BACKGROUND_LINK, AMBER, PreflightReason.BackgroundRestricted)
    }

    @Test
    fun `GIVEN the background link running under battery optimisation WHEN checking THEN it is green`() {
        val input = ALL_GREEN.copy(background = RUNNING.copy(batteryOptimized = true, notificationHidden = true))

        val result = rule.check(input)

        result[PreflightKind.BACKGROUND_LINK] shouldBeEqualTo green(PreflightKind.BACKGROUND_LINK)
    }

    @Test
    fun `GIVEN no SYS_STATUS received WHEN checking THEN the vehicle's pre-arm state is red`() {
        val input = ALL_GREEN.withTelemetry { copy(preArm = null) }

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            red(PreflightKind.VEHICLE_PREARM, PreflightReason.NotReceived)
    }

    @Test
    fun `GIVEN a pre-arm state as old as the battery's threshold WHEN checking THEN it is red as stale`() {
        val input = ALL_GREEN.withTelemetry { copy(preArm = Timed(PRE_ARM_HEALTHY, NOW - BATTERY_STALE)) }

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo red(PreflightKind.VEHICLE_PREARM, PreflightReason.Stale)
    }

    @Test
    fun `GIVEN the pre-arm bit not present WHEN checking THEN the vehicle's pre-arm state is red as unknown`() {
        val input = ALL_GREEN.withPreArm(PRE_ARM_HEALTHY.copy(present = false))

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            red(PreflightKind.VEHICLE_PREARM, PreflightReason.PreArmStateUnknown)
    }

    @Test
    fun `GIVEN the pre-arm bit present and not enabled WHEN checking THEN the arming checks are amber`() {
        val input = ALL_GREEN.withPreArm(PRE_ARM_HEALTHY.copy(enabled = false))

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            PreflightItem(PreflightKind.VEHICLE_PREARM, AMBER, PreflightReason.ArmingChecksDisabled)
    }

    @Test
    fun `GIVEN the pre-arm checks failing and no reason received WHEN checking THEN it is red without a reason`() {
        val input = ALL_GREEN.withPreArm(PRE_ARM_FAILING)

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            red(PreflightKind.VEHICLE_PREARM, PreflightReason.PreArmFailing(text = null, current = false))
    }

    @Test
    fun `GIVEN the pre-arm checks failing and a reason just received WHEN checking THEN it is red with that reason`() {
        val input = ALL_GREEN.withPreArm(PRE_ARM_FAILING).copy(preArmReason = preArmReason(NOW))

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            red(PreflightKind.VEHICLE_PREARM, PreflightReason.PreArmFailing(PRE_ARM_TEXT, current = true))
    }

    @Test
    fun `GIVEN the pre-arm checks failing and a reason 35 s old WHEN checking THEN its reason is marked old`() {
        val input = ALL_GREEN.withPreArm(PRE_ARM_FAILING).copy(preArmReason = preArmReason(NOW - PRE_ARM_MAX_AGE))

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo
            red(PreflightKind.VEHICLE_PREARM, PreflightReason.PreArmFailing(PRE_ARM_TEXT, current = false))
    }

    @Test
    fun `GIVEN the pre-arm checks healthy and an old reason held WHEN checking THEN it is green`() {
        val input = ALL_GREEN.copy(preArmReason = preArmReason(NOW))

        val result = rule.check(input)

        result[PreflightKind.VEHICLE_PREARM] shouldBeEqualTo green(PreflightKind.VEHICLE_PREARM)
    }

    @Test
    fun `GIVEN no GPS_RAW_INT received WHEN checking THEN the GPS is red as not received`() {
        val input = ALL_GREEN.withTelemetry { copy(gps = null) }

        val result = rule.check(input)

        result[PreflightKind.GPS] shouldBeEqualTo red(PreflightKind.GPS, PreflightReason.NotReceived)
    }

    @Test
    fun `GIVEN a 3D fix 2 s old WHEN checking THEN the GPS is red as stale, not as no fix`() {
        val input = ALL_GREEN.withTelemetry { copy(gps = Timed(GPS_3D, NOW - GPS_STALE)) }

        val result = rule.check(input)

        result[PreflightKind.GPS] shouldBeEqualTo red(PreflightKind.GPS, PreflightReason.Stale)
    }

    @Test
    fun `GIVEN a fresh 2D fix WHEN checking THEN the GPS is red as no 3D fix`() {
        val input = ALL_GREEN.withTelemetry { copy(gps = Timed(GPS_3D.copy(fixType = GpsFixType.Fix2d), NOW)) }

        val result = rule.check(input)

        result[PreflightKind.GPS] shouldBeEqualTo red(PreflightKind.GPS, PreflightReason.NoFix3d)
    }

    @Test
    fun `GIVEN no SYS_STATUS received WHEN checking THEN the battery is red as not received`() {
        val input = ALL_GREEN.withTelemetry { copy(battery = null) }

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.NotReceived)
    }

    @Test
    fun `GIVEN a full battery 3 s old WHEN checking THEN the battery is red as stale`() {
        val input = ALL_GREEN.withTelemetry { copy(battery = Timed(FULL_BATTERY, NOW - BATTERY_STALE)) }

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.Stale)
    }

    @Test
    fun `GIVEN no battery monitor WHEN checking THEN the battery is amber as unknown`() {
        val input = ALL_GREEN.withBattery(
            Battery(voltage = null, current = null, remaining = null, monitored = false, healthy = true),
        )

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo
            PreflightItem(PreflightKind.BATTERY, AMBER, PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_MONITOR))
    }

    @Test
    fun `GIVEN an unhealthy battery WHEN checking THEN the battery is red`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(healthy = false))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.BatteryUnhealthy)
    }

    @Test
    fun `GIVEN one voltage reading below the critical threshold WHEN checking THEN the battery is red, undebounced`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(voltage = 9.8))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.BatteryCritical)
    }

    @Test
    fun `GIVEN one voltage reading below the low threshold WHEN checking THEN the battery is red as low`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(voltage = 10.4))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.BatteryLow)
    }

    @Test
    fun `GIVEN a voltage at the low threshold WHEN checking THEN the battery is green, as ArduPilot compares below`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(voltage = LOW_VOLTS.toDouble()))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo green(PreflightKind.BATTERY)
    }

    @Test
    fun `GIVEN 10 percent left and no critical capacity set WHEN checking THEN the battery is red as critical`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(remaining = 10))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.BatteryCritical)
    }

    @Test
    fun `GIVEN 19 percent left, below the vehicle's 20 percent WHEN checking THEN the battery is red as low`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(remaining = 19))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo red(PreflightKind.BATTERY, PreflightReason.BatteryLow)
    }

    @Test
    fun `GIVEN a voltage and no remaining energy while no voltage limit is read WHEN checking THEN it is amber`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(remaining = null)).copy(battery = BatteryThresholds.UNKNOWN)

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo
            PreflightItem(PreflightKind.BATTERY, AMBER, PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_READING))
    }

    @Test
    fun `GIVEN a voltage and no remaining energy against a read voltage limit WHEN checking THEN it is green`() {
        val input = ALL_GREEN.withBattery(FULL_BATTERY.copy(remaining = null))

        val result = rule.check(input)

        result[PreflightKind.BATTERY] shouldBeEqualTo green(PreflightKind.BATTERY)
    }

    @Test
    fun `GIVEN the battery settings not read WHEN checking THEN the battery limits are amber as not read`() {
        val input = ALL_GREEN.copy(battery = BatteryThresholds.UNKNOWN)

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            PreflightItem(PreflightKind.BATTERY_LIMITS, AMBER, PreflightReason.LimitsNotRead)
    }

    @Test
    fun `GIVEN one battery setting still not read WHEN checking THEN the battery limits are amber as not read`() {
        val input = ALL_GREEN.copy(battery = ARMED_LIMITS.copy(capacityMah = null))

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            PreflightItem(PreflightKind.BATTERY_LIMITS, AMBER, PreflightReason.LimitsNotRead)
    }

    @Test
    fun `GIVEN no battery monitor WHEN checking THEN the battery limits are amber with no monitor`() {
        val input = ALL_GREEN.copy(battery = ARMED_LIMITS.copy(monitor = BatteryMonitor.None))

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            notArmed(low = FailsafeStatus.NO_MONITOR, critical = FailsafeStatus.NO_MONITOR)
    }

    @Test
    fun `GIVEN the low level's thresholds both disabled WHEN checking THEN the limits are amber with no threshold`() {
        val input = ALL_GREEN.copy(
            battery = ARMED_LIMITS.copy(
                low = ARMED_LIMITS.low.copy(
                    voltage = BatteryThreshold.Disabled,
                    remainingMah = BatteryThreshold.Disabled,
                ),
            ),
        )

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            notArmed(low = FailsafeStatus.NO_THRESHOLD, critical = FailsafeStatus.ARMED)
    }

    @Test
    fun `GIVEN the critical level only warning WHEN checking THEN the battery limits are amber as warn only`() {
        val input = ALL_GREEN.copy(
            battery = ARMED_LIMITS.copy(critical = ARMED_LIMITS.critical.copy(action = FailsafeAction.WarnOnly)),
        )

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            notArmed(low = FailsafeStatus.ARMED, critical = FailsafeStatus.WARN_ONLY)
    }

    @Test
    fun `GIVEN a low action the critical level may skip WHEN checking THEN the battery limits are amber as unknown`() {
        val input = ALL_GREEN.copy(
            battery = ARMED_LIMITS.copy(low = ARMED_LIMITS.low.copy(action = FailsafeAction.Acts(ACTION_TERMINATE))),
        )

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo
            notArmed(low = FailsafeStatus.UNKNOWN, critical = FailsafeStatus.ARMED)
    }

    @Test
    fun `GIVEN both battery levels armed WHEN checking THEN the battery limits are green`() {
        val input = ALL_GREEN.copy(battery = ARMED_LIMITS)

        val result = rule.check(input)

        result[PreflightKind.BATTERY_LIMITS] shouldBeEqualTo green(PreflightKind.BATTERY_LIMITS)
    }

    @Test
    fun `GIVEN no home WHEN checking THEN the home is red`() {
        val input = ALL_GREEN.withTelemetry { copy(home = null) }

        val result = rule.check(input)

        result[PreflightKind.HOME] shouldBeEqualTo red(PreflightKind.HOME, PreflightReason.HomeUnknown)
    }

    @Test
    fun `GIVEN no position received WHEN checking THEN the position is red as not received`() {
        val input = ALL_GREEN.withTelemetry { copy(position = null) }

        val result = rule.check(input)

        result[PreflightKind.POSITION] shouldBeEqualTo red(PreflightKind.POSITION, PreflightReason.NotReceived)
    }

    @Test
    fun `GIVEN a position 1 s old WHEN checking THEN the position is red as stale`() {
        val input = ALL_GREEN.withTelemetry { copy(position = Timed(POSITION, NOW - POSITION_STALE)) }

        val result = rule.check(input)

        result[PreflightKind.POSITION] shouldBeEqualTo red(PreflightKind.POSITION, PreflightReason.Stale)
    }

    @Test
    fun `GIVEN no attitude received WHEN checking THEN the attitude is red as not received`() {
        val input = ALL_GREEN.withTelemetry { copy(attitude = null) }

        val result = rule.check(input)

        result[PreflightKind.ATTITUDE] shouldBeEqualTo red(PreflightKind.ATTITUDE, PreflightReason.NotReceived)
    }

    @Test
    fun `GIVEN an attitude 500 ms old WHEN checking THEN the attitude is red as stale`() {
        val input = ALL_GREEN.withTelemetry { copy(attitude = Timed(LEVEL, NOW - ATTITUDE_STALE)) }

        val result = rule.check(input)

        result[PreflightKind.ATTITUDE] shouldBeEqualTo red(PreflightKind.ATTITUDE, PreflightReason.Stale)
    }

    @Test
    fun `GIVEN an emergency alert held WHEN checking THEN the alerts are red`() {
        val input = ALL_GREEN.copy(alerts = evaluated(Alert.BatteryVoltageCritical))

        val result = rule.check(input)

        result[PreflightKind.ALERTS] shouldBeEqualTo red(PreflightKind.ALERTS, PreflightReason.EmergencyAlert)
    }

    @Test
    fun `GIVEN only a caution alert WHEN checking THEN the alerts are green`() {
        val input = ALL_GREEN.copy(alerts = evaluated(Alert.GpsFixLost))

        val result = rule.check(input)

        result[PreflightKind.ALERTS] shouldBeEqualTo green(PreflightKind.ALERTS)
    }

    @Test
    fun `GIVEN the alerts not evaluated WHEN checking THEN they add nothing and say so`() {
        val input = ALL_GREEN.copy(alerts = Alerts.NotEvaluated)

        val result = rule.check(input)

        result[PreflightKind.ALERTS] shouldBeEqualTo
            PreflightItem(PreflightKind.ALERTS, GREEN, PreflightReason.AlertsNotEvaluated)
    }

    @Test
    fun `GIVEN its amber items acknowledged WHEN the voltage and its age change THEN they stay acknowledged`() {
        val first = rule.check(VOLTAGE_UNJUDGED)
        val acknowledged = first.ambers.fold(PreflightAcknowledgements()) { acks, item ->
            acks.acknowledge(item, first)
        }
        val next = rule.check(VOLTAGE_UNJUDGED.withTelemetry { copy(battery = Timed(LOWER_VOLTAGE, NOW - 1.seconds)) })

        val result = next.verdict(acknowledged.keptFor(next))

        result shouldBeEqualTo PreflightVerdict.Clear
    }
}

private operator fun PreflightCheck.get(kind: PreflightKind): PreflightItem = items.single { it.kind == kind }

private fun red(kind: PreflightKind, reason: PreflightReason) = PreflightItem(kind, RED, reason)

private fun green(kind: PreflightKind) = PreflightItem(kind, GREEN, PreflightReason.Ok)

private fun notArmed(low: FailsafeStatus, critical: FailsafeStatus) =
    PreflightItem(PreflightKind.BATTERY_LIMITS, AMBER, PreflightReason.FailsafeNotArmed(low, critical))

private val CLOCK = TestTimeSource()
private val NOW: TimeMark = CLOCK.markNow()

/** Each [StalenessRule.DEFAULT_THRESHOLDS] value, and [PreArmRule.DEFAULT_MAX_AGE]: the ages at which each ends. */
private val ATTITUDE_STALE = 500.milliseconds
private val POSITION_STALE = 1.seconds
private val GPS_STALE = 2.seconds
private val BATTERY_STALE = 3.seconds
private val PRE_ARM_MAX_AGE = 35.seconds

private const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"
private const val LOW_VOLTS = 10.5f
private const val CRITICAL_VOLTS = 9.9f
private const val CAPACITY_MAH = 5_000
private const val LOW_MAH = 1_000f
private const val MONITOR_VOLTAGE_AND_CURRENT = 4
private const val TIMER_SECONDS = 10

/** `BATT_FS_LOW_ACT` 2 (RTL) and `BATT_FS_CRT_ACT` 1 (Land); 6 (Terminate) is one the other level may skip. */
private const val ACTION_RTL = 2
private const val ACTION_LAND = 1
private const val ACTION_TERMINATE = 6

private val VEHICLE = Vehicle(
    state = VehicleState(SystemId(1), VehicleType.COPTER, FlightMode.Named("Loiter"), armed = false),
    link = VehicleLink.CONNECTED,
    lastHeartbeat = NOW,
    origin = DataOrigin.LIVE,
)

private val FULL_BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

private val PRE_ARM_HEALTHY = PreArmStatus(present = true, enabled = true, healthy = true)
private val PRE_ARM_FAILING = PRE_ARM_HEALTHY.copy(healthy = false)

private val GPS_3D = GpsStatus(
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

private val POSITION = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = 0.0,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = 0.0,
)

private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

/** Both levels act: a voltage and a capacity threshold below (20 % of 5000 mAh), and a critical voltage one. */
private val ARMED_LIMITS = BatteryThresholds(
    monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
    low = BatteryFailsafeLevel(
        BatteryThreshold.At(LOW_VOLTS),
        BatteryThreshold.At(LOW_MAH),
        FailsafeAction.Acts(ACTION_RTL),
    ),
    critical = BatteryFailsafeLevel(
        BatteryThreshold.At(CRITICAL_VOLTS),
        BatteryThreshold.Disabled,
        FailsafeAction.Acts(ACTION_LAND),
    ),
    lowVoltageTimer = LowVoltageTimer.Seconds(TIMER_SECONDS),
    capacityMah = CAPACITY_MAH,
    sentVoltage = SentVoltage.NEVER_ABOVE_COMPARED,
)

private val ANSWERED = UplinkHealth.HEALTHY.afterCommandAnswered()
private val RUNNING = BackgroundLinkStatus(running = true)

private val ALL_GREEN = PreflightInput(
    vehicle = VEHICLE,
    telemetry = Telemetry(
        origin = DataOrigin.LIVE,
        position = Timed(POSITION, NOW),
        attitude = Timed(LEVEL, NOW),
        battery = Timed(FULL_BATTERY, NOW),
        gps = Timed(GPS_3D, NOW),
        home = Timed(Home(latitude = -35.3633514, longitude = 149.1652411, altitudeMsl = 587.13), NOW),
        preArm = Timed(PRE_ARM_HEALTHY, NOW),
    ),
    battery = ARMED_LIMITS,
    uplink = ANSWERED,
    background = RUNNING,
    preArmReason = null,
    alerts = Alerts.Evaluated(emptyList()),
)

/** A voltage and no remaining energy, with nothing read to compare it with: the battery and its limits amber. */
private val VOLTAGE_UNJUDGED = ALL_GREEN.copy(
    telemetry = ALL_GREEN.telemetry?.copy(battery = Timed(FULL_BATTERY.copy(remaining = null), NOW)),
    battery = BatteryThresholds.UNKNOWN,
)

private val LOWER_VOLTAGE = FULL_BATTERY.copy(voltage = 12.4, remaining = null)

private fun PreflightInput.withTelemetry(change: Telemetry.() -> Telemetry) = copy(telemetry = telemetry?.change())

private fun PreflightInput.withPreArm(status: PreArmStatus) = withTelemetry { copy(preArm = Timed(status, NOW)) }

private fun PreflightInput.withBattery(battery: Battery) = withTelemetry { copy(battery = Timed(battery, NOW)) }

private fun preArmReason(receivedAt: TimeMark) =
    VehicleMessage(MessageSeverity.CRITICAL, PRE_ARM_TEXT, receivedAt, DataOrigin.LIVE)

private fun evaluated(alert: Alert) =
    Alerts.Evaluated(listOf(ActiveAlert(alert, occurrence = 1, raisedAt = NOW, origin = DataOrigin.LIVE)))
