package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.Rejection
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import kotlin.time.Duration

/** `MAV_COMP_ID_AUTOPILOT1` (`minimal.xml`): commands go to the autopilot and only its ACKs answer them. */
internal const val MAV_COMP_ID_AUTOPILOT1 = 1

internal const val MAV_CMD_NAV_RETURN_TO_LAUNCH = 20
internal const val MAV_CMD_NAV_LAND = 21
internal const val MAV_CMD_COMPONENT_ARM_DISARM = 400

/** Attempts of an RTL or land, with `confirmation` 0, 1, 2 (ADR 0008); arm is sent once. */
internal const val MAX_ATTEMPTS = 3

/** MAV_CMD_COMPONENT_ARM_DISARM param1: "0: disarm, 1: arm". */
private const val ARM = 1f

private const val MAV_RESULT_ACCEPTED = 0
private const val MAV_RESULT_TEMPORARILY_REJECTED = 1
private const val MAV_RESULT_IN_PROGRESS = 5
private val REJECTIONS = mapOf(
    2 to Rejection.DENIED,
    3 to Rejection.UNSUPPORTED,
    4 to Rejection.FAILED,
    6 to Rejection.CANCELLED,
    7 to Rejection.COMMAND_LONG_ONLY,
    8 to Rejection.COMMAND_INT_ONLY,
    9 to Rejection.UNSUPPORTED_FRAME,
    10 to Rejection.NOT_IN_CONTROL,
)

private const val MAX_PERCENT = 100

/** One COMMAND_LONG to send: its attempts and how long its late answer is listened for (ADR 0008). */
internal data class MavCommand(val id: Int, val attempts: Int, val lateWindow: Duration, val param1: Float = 0f)

internal val VehicleCommand.mavCommand: MavCommand
    get() = when (this) {
        VehicleCommand.ARM ->
            MavCommand(MAV_CMD_COMPONENT_ARM_DISARM, attempts = 1, CommandExchange.ARM_LATE_WINDOW, param1 = ARM)

        VehicleCommand.RETURN_TO_LAUNCH ->
            MavCommand(MAV_CMD_NAV_RETURN_TO_LAUNCH, MAX_ATTEMPTS, CommandExchange.LATE_WINDOW)

        VehicleCommand.LAND -> MavCommand(MAV_CMD_NAV_LAND, MAX_ATTEMPTS, CommandExchange.LATE_WINDOW)
    }

internal fun commandLongOf(systemId: SystemId, command: MavCommand, confirmation: Int) = CommandLong(
    targetSystem = systemId.value,
    targetComponent = MAV_COMP_ID_AUTOPILOT1,
    command = command.id,
    confirmation = confirmation,
    param1 = command.param1,
    // ArduPilot arms past its checks when param2 is 2989 or 21196 (`GCS.h` `magic_force_arm_value` and
    // `magic_force_arm_disarm_value`, `GCS_Common.cpp`): 0 runs them. RTL and LAND read no param on ArduCopter
    // (`GCS_MAVLink_Copter.cpp` only calls `set_mode`).
    param2 = 0f,
    param3 = 0f,
    param4 = 0f,
    param5 = 0f,
    param6 = 0f,
    param7 = 0f,
)

/** `MAV_RESULT_IN_PROGRESS`: not final, it "must terminate by sending a COMMAND_ACK message with final result". */
internal val CommandAck.isInProgress: Boolean
    get() = result == MAV_RESULT_IN_PROGRESS

/** `progress` is a percentage, or 255 when the vehicle does not know it. */
internal val CommandAck.percent: Int?
    get() = progress.takeIf { it in 0..MAX_PERCENT }

internal fun CommandAck.toResult(): CommandResult = when (result) {
    MAV_RESULT_ACCEPTED -> CommandResult.Accepted

    MAV_RESULT_TEMPORARILY_REJECTED -> CommandResult.Busy

    // The vehicle's explanation (`result_param2`, STATUSTEXT) is read with #100.
    else -> CommandResult.Rejected(REJECTIONS[result] ?: Rejection.UNKNOWN, reason = null)
}
