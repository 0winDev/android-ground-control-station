package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Attitude
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.GlobalPositionInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import com.owindev.gcs.core.mavlink.MavlinkMessage.VfrHud

/**
 * Turns valid frames into messages. Never throws: any frame gives a [MavlinkMessage].
 *
 * The payload of a message in [MavlinkMessages] is read at the message's full length:
 * - shorter (MAVLink 2 trailing-zero truncation, https://mavlink.io/en/guide/serialization.html, or
 *   missing extension fields): the missing bytes read as zero, since "the recipient will see zero
 *   values for the extensions fields" (https://mavlink.io/en/guide/define_xml_element.html, Message
 *   Extensions);
 * - longer (extension fields this codec does not know): the extra bytes are ignored, since "the fields
 *   will not be seen" (same page).
 *
 * Decoded messages read the frame's payload in place, so they allocate nothing beyond the message.
 * [Undecoded] always gets its own copy of the payload: it never shares the frame's array.
 *
 * Fields are little-endian and on the wire ordered by type size, not XML order; extension fields follow
 * them in XML order, never re-sorted by size.
 */
object MavlinkDecoder {

    private const val HEARTBEAT_ID = 0

    // HEARTBEAT wire layout: custom_mode (uint32) first, then the five uint8 fields.
    private const val CUSTOM_MODE_OFFSET = 0
    private const val TYPE_OFFSET = 4
    private const val AUTOPILOT_OFFSET = 5
    private const val BASE_MODE_OFFSET = 6
    private const val SYSTEM_STATUS_OFFSET = 7
    private const val MAVLINK_VERSION_OFFSET = 8

    private const val ATTITUDE_ID = 30

    // ATTITUDE wire layout: time_boot_ms (uint32), then the six floats in XML order (all 4 bytes).
    private const val ATT_TIME_BOOT_MS_OFFSET = 0
    private const val ATT_ROLL_OFFSET = 4
    private const val ATT_PITCH_OFFSET = 8
    private const val ATT_YAW_OFFSET = 12
    private const val ATT_ROLLSPEED_OFFSET = 16
    private const val ATT_PITCHSPEED_OFFSET = 20
    private const val ATT_YAWSPEED_OFFSET = 24

    private const val GLOBAL_POSITION_INT_ID = 33

    // GLOBAL_POSITION_INT wire layout: time_boot_ms (uint32) and the four int32s, then the three int16
    // speeds and hdg (uint16). The XML order is already sorted by size.
    private const val POS_TIME_BOOT_MS_OFFSET = 0
    private const val POS_LAT_OFFSET = 4
    private const val POS_LON_OFFSET = 8
    private const val POS_ALT_OFFSET = 12
    private const val POS_RELATIVE_ALT_OFFSET = 16
    private const val POS_VX_OFFSET = 20
    private const val POS_VY_OFFSET = 22
    private const val POS_VZ_OFFSET = 24
    private const val POS_HDG_OFFSET = 26

    private const val VFR_HUD_ID = 74

    // VFR_HUD wire layout: the four floats (airspeed, groundspeed, alt, climb), then heading (int16) and
    // throttle (uint16). In XML order heading and throttle come before alt; the size sort moves them last.
    private const val HUD_AIRSPEED_OFFSET = 0
    private const val HUD_GROUNDSPEED_OFFSET = 4
    private const val HUD_ALT_OFFSET = 8
    private const val HUD_CLIMB_OFFSET = 12
    private const val HUD_HEADING_OFFSET = 16
    private const val HUD_THROTTLE_OFFSET = 18

    private const val COMMAND_ACK_ID = 77

    // COMMAND_ACK wire layout: command (uint16), result, then the extensions in XML order: progress,
    // result_param2 (int32), target_system, target_component.
    private const val ACK_COMMAND_OFFSET = 0
    private const val ACK_RESULT_OFFSET = 2
    private const val ACK_PROGRESS_OFFSET = 3
    private const val ACK_RESULT_PARAM2_OFFSET = 4
    private const val ACK_TARGET_SYSTEM_OFFSET = 8
    private const val ACK_TARGET_COMPONENT_OFFSET = 9

    fun decode(frame: MavlinkFrame): MavlinkMessage {
        val fullLength = MavlinkMessages.fullLengthOf(frame.messageId)
            ?: return Undecoded(frame.messageId, frame.payload.copyOf())
        return when (frame.messageId) {
            HEARTBEAT_ID -> heartbeat(frame.payload)
            ATTITUDE_ID -> attitude(frame.payload)
            GLOBAL_POSITION_INT_ID -> globalPositionInt(frame.payload)
            VFR_HUD_ID -> vfrHud(frame.payload)
            COMMAND_ACK_ID -> commandAck(frame.payload)
            else -> Undecoded(frame.messageId, frame.payload.copyOf(fullLength))
        }
    }

    private fun heartbeat(payload: ByteArray) = Heartbeat(
        type = payload.uint8(TYPE_OFFSET),
        autopilot = payload.uint8(AUTOPILOT_OFFSET),
        baseMode = payload.uint8(BASE_MODE_OFFSET),
        customMode = payload.uint32(CUSTOM_MODE_OFFSET),
        systemStatus = payload.uint8(SYSTEM_STATUS_OFFSET),
        mavlinkVersion = payload.uint8(MAVLINK_VERSION_OFFSET),
    )

    private fun attitude(payload: ByteArray) = Attitude(
        timeBootMs = payload.uint32(ATT_TIME_BOOT_MS_OFFSET),
        roll = payload.float(ATT_ROLL_OFFSET),
        pitch = payload.float(ATT_PITCH_OFFSET),
        yaw = payload.float(ATT_YAW_OFFSET),
        rollspeed = payload.float(ATT_ROLLSPEED_OFFSET),
        pitchspeed = payload.float(ATT_PITCHSPEED_OFFSET),
        yawspeed = payload.float(ATT_YAWSPEED_OFFSET),
    )

    private fun globalPositionInt(payload: ByteArray) = GlobalPositionInt(
        timeBootMs = payload.uint32(POS_TIME_BOOT_MS_OFFSET),
        lat = payload.int32(POS_LAT_OFFSET),
        lon = payload.int32(POS_LON_OFFSET),
        alt = payload.int32(POS_ALT_OFFSET),
        relativeAlt = payload.int32(POS_RELATIVE_ALT_OFFSET),
        vx = payload.int16(POS_VX_OFFSET),
        vy = payload.int16(POS_VY_OFFSET),
        vz = payload.int16(POS_VZ_OFFSET),
        hdg = payload.uint16(POS_HDG_OFFSET),
    )

    private fun vfrHud(payload: ByteArray) = VfrHud(
        airspeed = payload.float(HUD_AIRSPEED_OFFSET),
        groundspeed = payload.float(HUD_GROUNDSPEED_OFFSET),
        heading = payload.int16(HUD_HEADING_OFFSET),
        throttle = payload.uint16(HUD_THROTTLE_OFFSET),
        alt = payload.float(HUD_ALT_OFFSET),
        climb = payload.float(HUD_CLIMB_OFFSET),
    )

    private fun commandAck(payload: ByteArray) = CommandAck(
        command = payload.uint16(ACK_COMMAND_OFFSET),
        result = payload.uint8(ACK_RESULT_OFFSET),
        progress = payload.uint8(ACK_PROGRESS_OFFSET),
        resultParam2 = payload.int32(ACK_RESULT_PARAM2_OFFSET),
        targetSystem = payload.uint8(ACK_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(ACK_TARGET_COMPONENT_OFFSET),
    )
}
