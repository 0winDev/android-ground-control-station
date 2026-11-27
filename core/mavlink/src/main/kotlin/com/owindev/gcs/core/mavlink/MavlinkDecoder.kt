package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Attitude
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.ExtendedSysState
import com.owindev.gcs.core.mavlink.MavlinkMessage.GlobalPositionInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.GpsRawInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.HomePosition
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCurrent
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemReached
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequest
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue
import com.owindev.gcs.core.mavlink.MavlinkMessage.StatusText
import com.owindev.gcs.core.mavlink.MavlinkMessage.SysStatus
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
// One reader per message, so each wire layout can be reviewed on its own.
@Suppress("TooManyFunctions")
object MavlinkDecoder {

    private const val HEARTBEAT_ID = 0

    // HEARTBEAT wire layout: custom_mode (uint32) first, then the five uint8 fields.
    private const val CUSTOM_MODE_OFFSET = 0
    private const val TYPE_OFFSET = 4
    private const val AUTOPILOT_OFFSET = 5
    private const val BASE_MODE_OFFSET = 6
    private const val SYSTEM_STATUS_OFFSET = 7
    private const val MAVLINK_VERSION_OFFSET = 8

    private const val SYS_STATUS_ID = 1

    // SYS_STATUS wire layout: the three uint32 sensor bitmaps, the nine 16-bit fields, battery_remaining
    // (int8), then the three uint32 extensions. The size sort moves battery_remaining after the 16-bit fields.
    private const val SYS_SENSORS_PRESENT_OFFSET = 0
    private const val SYS_SENSORS_ENABLED_OFFSET = 4
    private const val SYS_SENSORS_HEALTH_OFFSET = 8
    private const val SYS_LOAD_OFFSET = 12
    private const val SYS_VOLTAGE_BATTERY_OFFSET = 14
    private const val SYS_CURRENT_BATTERY_OFFSET = 16
    private const val SYS_DROP_RATE_COMM_OFFSET = 18
    private const val SYS_ERRORS_COMM_OFFSET = 20
    private const val SYS_ERRORS_COUNT1_OFFSET = 22
    private const val SYS_ERRORS_COUNT2_OFFSET = 24
    private const val SYS_ERRORS_COUNT3_OFFSET = 26
    private const val SYS_ERRORS_COUNT4_OFFSET = 28
    private const val SYS_BATTERY_REMAINING_OFFSET = 30
    private const val SYS_SENSORS_PRESENT_EXTENDED_OFFSET = 31
    private const val SYS_SENSORS_ENABLED_EXTENDED_OFFSET = 35
    private const val SYS_SENSORS_HEALTH_EXTENDED_OFFSET = 39

    private const val PARAM_VALUE_ID = 22

    // PARAM_VALUE wire layout: param_value (float), param_count and param_index (uint16), param_id (char[16]), then
    // param_type (uint8). The size sort moves param_id after the counters.
    private const val PARAM_VALUE_OFFSET = 0
    private const val PARAM_COUNT_OFFSET = 4
    private const val PARAM_INDEX_OFFSET = 6
    private const val PARAM_ID_OFFSET = 8
    private const val PARAM_ID_LENGTH = 16
    private const val PARAM_TYPE_OFFSET = 24

    private const val GPS_RAW_INT_ID = 24

    // GPS_RAW_INT wire layout: time_usec (uint64), the three int32s, the four uint16s, fix_type and
    // satellites_visible (uint8), then the extensions in XML order: alt_ellipsoid (int32), the four uint32
    // accuracies and yaw (uint16). The size sort moves fix_type after the 16-bit fields.
    private const val GPS_TIME_USEC_OFFSET = 0
    private const val GPS_LAT_OFFSET = 8
    private const val GPS_LON_OFFSET = 12
    private const val GPS_ALT_OFFSET = 16
    private const val GPS_EPH_OFFSET = 20
    private const val GPS_EPV_OFFSET = 22
    private const val GPS_VEL_OFFSET = 24
    private const val GPS_COG_OFFSET = 26
    private const val GPS_FIX_TYPE_OFFSET = 28
    private const val GPS_SATELLITES_VISIBLE_OFFSET = 29
    private const val GPS_ALT_ELLIPSOID_OFFSET = 30
    private const val GPS_H_ACC_OFFSET = 34
    private const val GPS_V_ACC_OFFSET = 38
    private const val GPS_VEL_ACC_OFFSET = 42
    private const val GPS_HDG_ACC_OFFSET = 46
    private const val GPS_YAW_OFFSET = 50

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

    private const val HOME_POSITION_ID = 242

    // HOME_POSITION: every base field is 4 bytes, so XML order is wire order; the uint64 extension still comes last.
    private const val HOME_LATITUDE_OFFSET = 0
    private const val HOME_LONGITUDE_OFFSET = 4
    private const val HOME_ALTITUDE_OFFSET = 8
    private const val HOME_X_OFFSET = 12
    private const val HOME_Y_OFFSET = 16
    private const val HOME_Z_OFFSET = 20
    private const val HOME_Q1_OFFSET = 24
    private const val HOME_Q2_OFFSET = 28
    private const val HOME_Q3_OFFSET = 32
    private const val HOME_Q4_OFFSET = 36
    private const val HOME_APPROACH_X_OFFSET = 40
    private const val HOME_APPROACH_Y_OFFSET = 44
    private const val HOME_APPROACH_Z_OFFSET = 48
    private const val HOME_TIME_USEC_OFFSET = 52

    private const val EXTENDED_SYS_STATE_ID = 245
    private const val VTOL_STATE_OFFSET = 0
    private const val LANDED_STATE_OFFSET = 1

    private const val STATUSTEXT_ID = 253

    // STATUSTEXT: every base field is 1 byte, so XML order is wire order; the extensions id (uint16) and
    // chunk_seq follow.
    private const val TEXT_SEVERITY_OFFSET = 0
    private const val TEXT_OFFSET = 1
    private const val TEXT_LENGTH = 50
    private const val TEXT_ID_OFFSET = 51
    private const val TEXT_CHUNK_SEQ_OFFSET = 53

    private const val MISSION_REQUEST_ID = 40
    private const val MISSION_CURRENT_ID = 42
    private const val MISSION_COUNT_ID = 44
    private const val MISSION_ITEM_REACHED_ID = 46
    private const val MISSION_ACK_ID = 47
    private const val MISSION_REQUEST_INT_ID = 51
    private const val MISSION_ITEM_INT_ID = 73

    // MISSION_REQUEST and MISSION_REQUEST_INT share a layout: seq (uint16) first, the targets, then the extension
    // mission_type.
    private const val REQUEST_SEQ_OFFSET = 0
    private const val REQUEST_TARGET_SYSTEM_OFFSET = 2
    private const val REQUEST_TARGET_COMPONENT_OFFSET = 3
    private const val REQUEST_MISSION_TYPE_OFFSET = 4

    // MISSION_COUNT: count (uint16) first, the targets, then the extensions mission_type and opaque_id (uint32), in
    // XML order.
    private const val COUNT_OFFSET = 0
    private const val COUNT_TARGET_SYSTEM_OFFSET = 2
    private const val COUNT_TARGET_COMPONENT_OFFSET = 3
    private const val COUNT_MISSION_TYPE_OFFSET = 4
    private const val COUNT_OPAQUE_ID_OFFSET = 5

    // MISSION_ACK: the targets and type, then the extensions mission_type and opaque_id (uint32), in XML order.
    private const val MISSION_ACK_TARGET_SYSTEM_OFFSET = 0
    private const val MISSION_ACK_TARGET_COMPONENT_OFFSET = 1
    private const val MISSION_ACK_TYPE_OFFSET = 2
    private const val MISSION_ACK_MISSION_TYPE_OFFSET = 3
    private const val MISSION_ACK_OPAQUE_ID_OFFSET = 4

    // MISSION_ITEM_INT: param1..param4 (float), x and y (int32), z (float), seq and command (uint16), the six uint8
    // fields in XML order, then the extension mission_type.
    private const val ITEM_PARAM1_OFFSET = 0
    private const val ITEM_PARAM2_OFFSET = 4
    private const val ITEM_PARAM3_OFFSET = 8
    private const val ITEM_PARAM4_OFFSET = 12
    private const val ITEM_X_OFFSET = 16
    private const val ITEM_Y_OFFSET = 20
    private const val ITEM_Z_OFFSET = 24
    private const val ITEM_SEQ_OFFSET = 28
    private const val ITEM_COMMAND_OFFSET = 30
    private const val ITEM_TARGET_SYSTEM_OFFSET = 32
    private const val ITEM_TARGET_COMPONENT_OFFSET = 33
    private const val ITEM_FRAME_OFFSET = 34
    private const val ITEM_CURRENT_OFFSET = 35
    private const val ITEM_AUTOCONTINUE_OFFSET = 36
    private const val ITEM_MISSION_TYPE_OFFSET = 37

    // MISSION_CURRENT: seq (uint16), then the extensions in XML order: total (uint16), mission_state, mission_mode,
    // mission_id, fence_id, rally_points_id (uint32).
    private const val CURRENT_SEQ_OFFSET = 0
    private const val CURRENT_TOTAL_OFFSET = 2
    private const val CURRENT_MISSION_STATE_OFFSET = 4
    private const val CURRENT_MISSION_MODE_OFFSET = 5
    private const val CURRENT_MISSION_ID_OFFSET = 6
    private const val CURRENT_FENCE_ID_OFFSET = 10
    private const val CURRENT_RALLY_POINTS_ID_OFFSET = 14

    private const val REACHED_SEQ_OFFSET = 0

    // One flat branch per message, as in MavlinkMessages.definitionOf: the complexity grows with the table, not with
    // any logic.
    @Suppress("CyclomaticComplexMethod")
    fun decode(frame: MavlinkFrame): MavlinkMessage {
        val fullLength = MavlinkMessages.fullLengthOf(frame.messageId)
            ?: return Undecoded(frame.messageId, frame.payload.copyOf())
        return when (frame.messageId) {
            HEARTBEAT_ID -> heartbeat(frame.payload)
            SYS_STATUS_ID -> sysStatus(frame.payload)
            PARAM_VALUE_ID -> paramValue(frame.payload)
            GPS_RAW_INT_ID -> gpsRawInt(frame.payload)
            ATTITUDE_ID -> attitude(frame.payload)
            GLOBAL_POSITION_INT_ID -> globalPositionInt(frame.payload)
            VFR_HUD_ID -> vfrHud(frame.payload)
            COMMAND_ACK_ID -> commandAck(frame.payload)
            HOME_POSITION_ID -> homePosition(frame.payload)
            EXTENDED_SYS_STATE_ID -> extendedSysState(frame.payload)
            STATUSTEXT_ID -> statusText(frame.payload)
            MISSION_REQUEST_ID -> missionRequest(frame.payload)
            MISSION_COUNT_ID -> missionCount(frame.payload)
            MISSION_ACK_ID -> missionAck(frame.payload)
            MISSION_REQUEST_INT_ID -> missionRequestInt(frame.payload)
            MISSION_ITEM_INT_ID -> missionItemInt(frame.payload)
            MISSION_CURRENT_ID -> missionCurrent(frame.payload)
            MISSION_ITEM_REACHED_ID -> MissionItemReached(seq = frame.payload.uint16(REACHED_SEQ_OFFSET))
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

    private fun sysStatus(payload: ByteArray) = SysStatus(
        onboardControlSensorsPresent = payload.uint32(SYS_SENSORS_PRESENT_OFFSET),
        onboardControlSensorsEnabled = payload.uint32(SYS_SENSORS_ENABLED_OFFSET),
        onboardControlSensorsHealth = payload.uint32(SYS_SENSORS_HEALTH_OFFSET),
        load = payload.uint16(SYS_LOAD_OFFSET),
        voltageBattery = payload.uint16(SYS_VOLTAGE_BATTERY_OFFSET),
        currentBattery = payload.int16(SYS_CURRENT_BATTERY_OFFSET),
        batteryRemaining = payload.int8(SYS_BATTERY_REMAINING_OFFSET),
        dropRateComm = payload.uint16(SYS_DROP_RATE_COMM_OFFSET),
        errorsComm = payload.uint16(SYS_ERRORS_COMM_OFFSET),
        errorsCount1 = payload.uint16(SYS_ERRORS_COUNT1_OFFSET),
        errorsCount2 = payload.uint16(SYS_ERRORS_COUNT2_OFFSET),
        errorsCount3 = payload.uint16(SYS_ERRORS_COUNT3_OFFSET),
        errorsCount4 = payload.uint16(SYS_ERRORS_COUNT4_OFFSET),
        onboardControlSensorsPresentExtended = payload.uint32(SYS_SENSORS_PRESENT_EXTENDED_OFFSET),
        onboardControlSensorsEnabledExtended = payload.uint32(SYS_SENSORS_ENABLED_EXTENDED_OFFSET),
        onboardControlSensorsHealthExtended = payload.uint32(SYS_SENSORS_HEALTH_EXTENDED_OFFSET),
    )

    private fun paramValue(payload: ByteArray) = ParamValue(
        paramId = payload.utf8(PARAM_ID_OFFSET, payload.textEnd(PARAM_ID_OFFSET, PARAM_ID_LENGTH)),
        value = payload.float(PARAM_VALUE_OFFSET),
        type = payload.uint8(PARAM_TYPE_OFFSET),
        count = payload.uint16(PARAM_COUNT_OFFSET),
        index = payload.uint16(PARAM_INDEX_OFFSET),
    )

    private fun gpsRawInt(payload: ByteArray) = GpsRawInt(
        timeUsec = payload.uint64(GPS_TIME_USEC_OFFSET),
        fixType = payload.uint8(GPS_FIX_TYPE_OFFSET),
        lat = payload.int32(GPS_LAT_OFFSET),
        lon = payload.int32(GPS_LON_OFFSET),
        alt = payload.int32(GPS_ALT_OFFSET),
        eph = payload.uint16(GPS_EPH_OFFSET),
        epv = payload.uint16(GPS_EPV_OFFSET),
        vel = payload.uint16(GPS_VEL_OFFSET),
        cog = payload.uint16(GPS_COG_OFFSET),
        satellitesVisible = payload.uint8(GPS_SATELLITES_VISIBLE_OFFSET),
        altEllipsoid = payload.int32(GPS_ALT_ELLIPSOID_OFFSET),
        hAcc = payload.uint32(GPS_H_ACC_OFFSET),
        vAcc = payload.uint32(GPS_V_ACC_OFFSET),
        velAcc = payload.uint32(GPS_VEL_ACC_OFFSET),
        hdgAcc = payload.uint32(GPS_HDG_ACC_OFFSET),
        yaw = payload.uint16(GPS_YAW_OFFSET),
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

    private fun missionRequest(payload: ByteArray) = MissionRequest(
        targetSystem = payload.uint8(REQUEST_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(REQUEST_TARGET_COMPONENT_OFFSET),
        seq = payload.uint16(REQUEST_SEQ_OFFSET),
        missionType = payload.uint8(REQUEST_MISSION_TYPE_OFFSET),
    )

    private fun missionRequestInt(payload: ByteArray) = MissionRequestInt(
        targetSystem = payload.uint8(REQUEST_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(REQUEST_TARGET_COMPONENT_OFFSET),
        seq = payload.uint16(REQUEST_SEQ_OFFSET),
        missionType = payload.uint8(REQUEST_MISSION_TYPE_OFFSET),
    )

    private fun missionCount(payload: ByteArray) = MissionCount(
        targetSystem = payload.uint8(COUNT_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(COUNT_TARGET_COMPONENT_OFFSET),
        count = payload.uint16(COUNT_OFFSET),
        missionType = payload.uint8(COUNT_MISSION_TYPE_OFFSET),
        opaqueId = payload.uint32(COUNT_OPAQUE_ID_OFFSET),
    )

    private fun missionAck(payload: ByteArray) = MissionAck(
        targetSystem = payload.uint8(MISSION_ACK_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(MISSION_ACK_TARGET_COMPONENT_OFFSET),
        type = payload.uint8(MISSION_ACK_TYPE_OFFSET),
        missionType = payload.uint8(MISSION_ACK_MISSION_TYPE_OFFSET),
        opaqueId = payload.uint32(MISSION_ACK_OPAQUE_ID_OFFSET),
    )

    private fun missionItemInt(payload: ByteArray) = MissionItemInt(
        targetSystem = payload.uint8(ITEM_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(ITEM_TARGET_COMPONENT_OFFSET),
        seq = payload.uint16(ITEM_SEQ_OFFSET),
        frame = payload.uint8(ITEM_FRAME_OFFSET),
        command = payload.uint16(ITEM_COMMAND_OFFSET),
        current = payload.uint8(ITEM_CURRENT_OFFSET),
        autocontinue = payload.uint8(ITEM_AUTOCONTINUE_OFFSET),
        param1 = payload.float(ITEM_PARAM1_OFFSET),
        param2 = payload.float(ITEM_PARAM2_OFFSET),
        param3 = payload.float(ITEM_PARAM3_OFFSET),
        param4 = payload.float(ITEM_PARAM4_OFFSET),
        x = payload.int32(ITEM_X_OFFSET),
        y = payload.int32(ITEM_Y_OFFSET),
        z = payload.float(ITEM_Z_OFFSET),
        missionType = payload.uint8(ITEM_MISSION_TYPE_OFFSET),
    )

    private fun missionCurrent(payload: ByteArray) = MissionCurrent(
        seq = payload.uint16(CURRENT_SEQ_OFFSET),
        total = payload.uint16(CURRENT_TOTAL_OFFSET),
        missionState = payload.uint8(CURRENT_MISSION_STATE_OFFSET),
        missionMode = payload.uint8(CURRENT_MISSION_MODE_OFFSET),
        missionId = payload.uint32(CURRENT_MISSION_ID_OFFSET),
        fenceId = payload.uint32(CURRENT_FENCE_ID_OFFSET),
        rallyPointsId = payload.uint32(CURRENT_RALLY_POINTS_ID_OFFSET),
    )

    private fun extendedSysState(payload: ByteArray) = ExtendedSysState(
        vtolState = payload.uint8(VTOL_STATE_OFFSET),
        landedState = payload.uint8(LANDED_STATE_OFFSET),
    )

    private fun homePosition(payload: ByteArray) = HomePosition(
        latitude = payload.int32(HOME_LATITUDE_OFFSET),
        longitude = payload.int32(HOME_LONGITUDE_OFFSET),
        altitude = payload.int32(HOME_ALTITUDE_OFFSET),
        x = payload.float(HOME_X_OFFSET),
        y = payload.float(HOME_Y_OFFSET),
        z = payload.float(HOME_Z_OFFSET),
        q1 = payload.float(HOME_Q1_OFFSET),
        q2 = payload.float(HOME_Q2_OFFSET),
        q3 = payload.float(HOME_Q3_OFFSET),
        q4 = payload.float(HOME_Q4_OFFSET),
        approachX = payload.float(HOME_APPROACH_X_OFFSET),
        approachY = payload.float(HOME_APPROACH_Y_OFFSET),
        approachZ = payload.float(HOME_APPROACH_Z_OFFSET),
        timeUsec = payload.uint64(HOME_TIME_USEC_OFFSET),
    )

    private fun statusText(payload: ByteArray): StatusText {
        val textEnd = payload.textEnd(TEXT_OFFSET, TEXT_LENGTH)
        return StatusText(
            severity = payload.uint8(TEXT_SEVERITY_OFFSET),
            text = payload.utf8(TEXT_OFFSET, textEnd),
            id = payload.uint16(TEXT_ID_OFFSET),
            chunkSeq = payload.uint8(TEXT_CHUNK_SEQ_OFFSET),
            endsInField = textEnd < TEXT_OFFSET + TEXT_LENGTH,
        )
    }
}
