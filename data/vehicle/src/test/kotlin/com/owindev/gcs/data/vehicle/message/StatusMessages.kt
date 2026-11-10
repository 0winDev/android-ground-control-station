package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import java.net.InetAddress
import java.net.InetSocketAddress

internal const val MESSAGE_SYSTEM_1 = 1
internal const val MESSAGE_SYSTEM_2 = 2
internal const val MAV_COMP_ID_AUTOPILOT1 = 1
internal const val MAV_COMP_ID_GIMBAL = 154

/** `MAV_SEVERITY_CRITICAL`, as ArduPilot sends its pre-arm failures (`AP_Arming::check_failed`). */
internal const val MAV_SEVERITY_CRITICAL = 2

/** `MAV_SYS_STATUS_PREARM_CHECK` in `common.xml`. */
internal const val PREARM_CHECK_BIT = 0x10000000L

internal const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)

/** HEARTBEAT `base_mode` of ArduCopter SITL (`core/mavlink` fixtures): 81 disarmed, 217 adds `SAFETY_ARMED`. */
private const val BASE_MODE_DISARMED = 81
private const val BASE_MODE_ARMED = 217
private const val MAV_TYPE_QUADROTOR = 2
private const val MAV_AUTOPILOT_ARDUPILOTMEGA = 3
private const val MAV_STATE_STANDBY = 3

internal fun received(
    message: MavlinkMessage,
    session: SessionId,
    systemId: Int = MESSAGE_SYSTEM_1,
    componentId: Int = MAV_COMP_ID_AUTOPILOT1,
) = ReceivedMessage(systemId, componentId, message, SENDER, session)

internal fun wholeText(text: String, severity: Int = MAV_SEVERITY_CRITICAL) =
    MavlinkMessage.StatusText(severity, text, id = 0, chunkSeq = 0, endsInField = true)

internal fun heartbeat(armed: Boolean) = MavlinkMessage.Heartbeat(
    type = MAV_TYPE_QUADROTOR,
    autopilot = MAV_AUTOPILOT_ARDUPILOTMEGA,
    baseMode = if (armed) BASE_MODE_ARMED else BASE_MODE_DISARMED,
    customMode = 0,
    systemStatus = MAV_STATE_STANDBY,
    mavlinkVersion = 3,
)

/** SYS_STATUS with only the pre-arm bit set as given in each mask, everything else zero. */
internal fun preArmStatus(enabled: Boolean, healthy: Boolean) = MavlinkMessage.SysStatus(
    onboardControlSensorsPresent = PREARM_CHECK_BIT,
    onboardControlSensorsEnabled = if (enabled) PREARM_CHECK_BIT else 0,
    onboardControlSensorsHealth = if (healthy) PREARM_CHECK_BIT else 0,
    load = 0,
    voltageBattery = 0,
    currentBattery = 0,
    batteryRemaining = 0,
    dropRateComm = 0,
    errorsComm = 0,
    errorsCount1 = 0,
    errorsCount2 = 0,
    errorsCount3 = 0,
    errorsCount4 = 0,
    onboardControlSensorsPresentExtended = 0,
    onboardControlSensorsEnabledExtended = 0,
    onboardControlSensorsHealthExtended = 0,
)
