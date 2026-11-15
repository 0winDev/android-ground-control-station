package com.owindev.gcs.core.domain.preflight

import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.alert.BatteryAlertLimits
import com.owindev.gcs.core.domain.alert.BatteryLevelLimits
import com.owindev.gcs.core.domain.alert.GcsEnergyThresholds
import com.owindev.gcs.core.domain.alert.VoltageLimit
import com.owindev.gcs.core.domain.alert.anyLimitUnread
import com.owindev.gcs.core.domain.alert.batteryAlertLimitsOf
import com.owindev.gcs.core.domain.alert.isCrossedBy
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import com.owindev.gcs.core.domain.background.BackgroundLinkWarning
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRule
import com.owindev.gcs.core.domain.link.UplinkWarning
import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.preflight.PreflightColour.AMBER
import com.owindev.gcs.core.domain.preflight.PreflightColour.GREEN
import com.owindev.gcs.core.domain.preflight.PreflightColour.RED
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.isFix3d
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [PreflightRule] judges: the active [vehicle] and its [telemetry] (`null` when there is none), its [battery]
 * settings as read, the [uplink], the [background] link, the latest [preArmReason] and the [alerts]. [connection]
 * names the active vehicle's current connection and changes on every CONNECTED and every session, so that no
 * acknowledgement outlives it ([PreflightAcknowledgements]).
 */
data class PreflightInput(
    val connection: Long,
    val vehicle: Vehicle?,
    val telemetry: Telemetry?,
    val battery: BatteryThresholds,
    val uplink: UplinkHealth,
    val background: BackgroundLinkStatus,
    val preArmReason: VehicleMessage?,
    val alerts: Alerts,
)

/**
 * The pre-flight check (REQ-048), the only place that decides it: the data layer applies it before arm and take off,
 * the screen shows it. Every age is read now, so a caller judges it again right before it acts. The battery is judged
 * on its latest fresh reading, without the alerts' debounce, and the vehicle's pre-arm state only by SYS_STATUS: its
 * `PreArm:` text only names the reason.
 */
class PreflightRule(
    private val linkLoss: LinkLossRule,
    private val staleness: StalenessRule,
    private val preArm: PreArmRule,
    private val uplinkRule: UplinkRule,
    private val backgroundRule: BackgroundLinkRule,
    private val energyDefaults: GcsEnergyThresholds,
) {

    fun check(input: PreflightInput): PreflightCheck = PreflightCheck(
        items = PreflightKind.entries.map { kind ->
            val (colour, reason) = judge(kind, input)
            PreflightItem(kind, colour, reason)
        },
        connection = input.connection,
    )

    private fun judge(kind: PreflightKind, input: PreflightInput): Judgement = when (kind) {
        PreflightKind.LINK -> link(input.vehicle)
        PreflightKind.UPLINK -> uplink(input.uplink)
        PreflightKind.BACKGROUND_LINK -> background(input.background)
        PreflightKind.VEHICLE_PREARM -> vehiclePreArm(input.telemetry, input.preArmReason)
        PreflightKind.GPS -> gps(input.telemetry)
        PreflightKind.BATTERY -> battery(input)
        PreflightKind.BATTERY_LIMITS -> batteryLimits(input.battery)
        PreflightKind.HOME -> if (input.telemetry?.home == null) RED to PreflightReason.HomeUnknown else OK
        PreflightKind.POSITION -> fresh(input.telemetry, TelemetryKind.POSITION)
        PreflightKind.ATTITUDE -> fresh(input.telemetry, TelemetryKind.ATTITUDE)
        PreflightKind.ALERTS -> alerts(input.alerts)
    }

    /** The watchdog's verdict and the HEARTBEAT's age now: the watchdog's timer can run late after a sleep (#12). */
    private fun link(vehicle: Vehicle?): Judgement = when {
        vehicle == null -> RED to PreflightReason.NoActiveVehicle
        vehicle.origin == DataOrigin.REPLAY -> RED to PreflightReason.Replay
        linkLoss.isLost(vehicle) -> RED to PreflightReason.LinkLost
        else -> OK
    }

    private fun uplink(health: UplinkHealth): Judgement = when (uplinkRule.warningOf(health)) {
        UplinkWarning.SendsFailing -> RED to PreflightReason.UplinkSendsFailing
        is UplinkWarning.CommandUnanswered -> RED to PreflightReason.UplinkCommandUnanswered
        null -> if (uplinkRule.isConfirmed(health)) OK else RED to PreflightReason.NotAnsweredYet
    }

    /** Only a running link is green: no warning alone means only no known restriction (REQ-046). */
    private fun background(status: BackgroundLinkStatus): Judgement = when {
        !status.running -> RED to PreflightReason.BackgroundLinkNotRunning

        BackgroundLinkWarning.BACKGROUND_RESTRICTED in backgroundRule.warningsFor(status) ->
            AMBER to PreflightReason.BackgroundRestricted

        else -> OK
    }

    private fun vehiclePreArm(telemetry: Telemetry?, reason: VehicleMessage?): Judgement {
        val timed = telemetry?.preArm ?: return RED to PreflightReason.NotReceived
        val status = timed.value
        return when {
            timed.receivedAt.elapsedNow() >= PRE_ARM_MAX_AGE -> RED to PreflightReason.Stale

            !status.present -> RED to PreflightReason.PreArmStateUnknown

            !status.enabled -> AMBER to PreflightReason.ArmingChecksDisabled

            !status.healthy -> RED to PreflightReason.PreArmFailing(
                text = reason?.text,
                current = reason?.let(preArm::isCurrent) == true,
            )

            else -> OK
        }
    }

    private fun gps(telemetry: Telemetry?): Judgement {
        val timed = telemetry?.gps ?: return RED to PreflightReason.NotReceived
        return when {
            timed.isStale(TelemetryKind.GPS) -> RED to PreflightReason.Stale
            !timed.value.fixType.isFix3d -> RED to PreflightReason.NoFix3d
            else -> OK
        }
    }

    private fun battery(input: PreflightInput): Judgement {
        val timed = input.telemetry?.battery ?: return RED to PreflightReason.NotReceived
        val battery = timed.value
        val limits = batteryAlertLimitsOf(input.battery, energyDefaults)
        return when {
            timed.isStale(TelemetryKind.BATTERY) -> RED to PreflightReason.Stale
            !battery.monitored -> AMBER to PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_MONITOR)
            !battery.healthy -> RED to PreflightReason.BatteryUnhealthy
            battery.crosses(limits.critical) -> RED to PreflightReason.BatteryCritical
            battery.crosses(limits.low) -> RED to PreflightReason.BatteryLow
            !battery.isJudgedBy(limits) -> AMBER to PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_READING)
            else -> OK
        }
    }

    private fun fresh(telemetry: Telemetry?, kind: TelemetryKind): Judgement {
        val timed = telemetry?.get(kind) ?: return RED to PreflightReason.NotReceived
        return if (timed.isStale(kind)) RED to PreflightReason.Stale else OK
    }

    private fun Timed<*>.isStale(kind: TelemetryKind): Boolean = staleness.isStale(kind, receivedAt.elapsedNow())

    companion object {
        /**
         * The gate's own bound on the pre-arm state, never the display's (#112): three periods of the 1 Hz SYS_STATUS
         * it comes in, so one or two lost frames never block, and no older than the link-loss timeout.
         */
        val PRE_ARM_MAX_AGE: Duration = 3.seconds
    }
}

private fun batteryLimits(battery: BatteryThresholds): Judgement = when {
    battery.anyLimitUnread() -> AMBER to PreflightReason.LimitsNotRead

    battery.lowStatus != FailsafeStatus.ARMED || battery.criticalStatus != FailsafeStatus.ARMED ->
        AMBER to PreflightReason.FailsafeNotArmed(battery.lowStatus, battery.criticalStatus)

    else -> OK
}

private fun alerts(alerts: Alerts): Judgement = when (alerts) {
    Alerts.NotEvaluated -> GREEN to PreflightReason.AlertsNotEvaluated

    is Alerts.Evaluated ->
        if (alerts.active.any { it.alert.level == AlertLevel.EMERGENCY }) {
            RED to PreflightReason.EmergencyAlert
        } else {
            OK
        }
}

/** ArduPilot compares the voltage below its threshold; the debounce is left out before arming. */
private fun Battery.crosses(level: BatteryLevelLimits): Boolean {
    val voltageLimit = level.voltage
    val belowVoltage = voltage != null && voltageLimit is VoltageLimit.Below && voltage < voltageLimit.volts
    return belowVoltage || remaining?.let(level.energy::isCrossedBy) == true
}

private fun Battery.isJudgedBy(limits: BatteryAlertLimits): Boolean = remaining != null ||
    (voltage != null && listOf(limits.low, limits.critical).any { it.voltage is VoltageLimit.Below })

private typealias Judgement = Pair<PreflightColour, PreflightReason>

private val OK: Judgement = GREEN to PreflightReason.Ok
