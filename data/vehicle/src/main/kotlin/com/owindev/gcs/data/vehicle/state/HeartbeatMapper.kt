package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.data.vehicle.link.ReceivedMessage

/**
 * Maps a received HEARTBEAT to the state of the vehicle that sent it.
 *
 * Only the autopilot describes the vehicle: other components of the same system (gimbal, companion
 * computer) and other ground stations send HEARTBEATs too, and must not overwrite it.
 *
 * Enum values from `minimal.xml`,
 * https://github.com/mavlink/mavlink/blob/master/message_definitions/v1.0/minimal.xml.
 */
internal object HeartbeatMapper {

    private const val MAV_COMP_ID_AUTOPILOT1 = 1
    private const val MAV_TYPE_GCS = 6
    private const val MAV_AUTOPILOT_ARDUPILOTMEGA = 3
    private const val MAV_AUTOPILOT_INVALID = 8
    private const val MAV_MODE_FLAG_SAFETY_ARMED = 128

    private const val MAV_TYPE_QUADROTOR = 2
    private const val MAV_TYPE_COAXIAL = 3
    private const val MAV_TYPE_HELICOPTER = 4
    private const val MAV_TYPE_HEXAROTOR = 13
    private const val MAV_TYPE_OCTOROTOR = 14
    private const val MAV_TYPE_TRICOPTER = 15
    private const val MAV_TYPE_DODECAROTOR = 29
    private const val MAV_TYPE_DECAROTOR = 35

    /** The `MAV_TYPE`s ArduCopter reports, all sharing its flight-mode numbers. */
    private val copterTypes = setOf(
        MAV_TYPE_QUADROTOR,
        MAV_TYPE_COAXIAL,
        MAV_TYPE_HELICOPTER,
        MAV_TYPE_HEXAROTOR,
        MAV_TYPE_OCTOROTOR,
        MAV_TYPE_TRICOPTER,
        MAV_TYPE_DODECAROTOR,
        MAV_TYPE_DECAROTOR,
    )

    /** The sender's state, or `null` if [received] is not a HEARTBEAT from a vehicle's autopilot. */
    fun vehicleStateOf(received: ReceivedMessage): VehicleState? {
        val heartbeat = (received.message as? Heartbeat)
            ?.takeIf { it.isFromAutopilot(received.componentId) }
            ?: return null
        val isCopter = heartbeat.type in copterTypes
        return VehicleState(
            systemId = SystemId(received.systemId),
            type = if (isCopter) VehicleType.COPTER else VehicleType.OTHER,
            flightMode = heartbeat.flightMode(isCopter),
            armed = heartbeat.baseMode and MAV_MODE_FLAG_SAFETY_ARMED != 0,
        )
    }

    private fun Heartbeat.isFromAutopilot(componentId: Int): Boolean =
        componentId == MAV_COMP_ID_AUTOPILOT1 && type != MAV_TYPE_GCS && autopilot != MAV_AUTOPILOT_INVALID

    // Mode numbers are only meaningful for the autopilot and vehicle type they belong to (ADR 0003).
    private fun Heartbeat.flightMode(isCopter: Boolean): FlightMode =
        if (isCopter && autopilot == MAV_AUTOPILOT_ARDUPILOTMEGA) {
            ArduCopterFlightModes.flightModeOf(customMode)
        } else {
            FlightMode.Unknown(customMode)
        }
}
