package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestList
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead

/**
 * Turns messages into unsigned MAVLink 2 frames ready to send (https://mavlink.io/en/guide/serialization.html).
 *
 * Frame layout: `0xFD`, LEN, incompat flags (0), compat flags (0), SEQ, SYSID, COMPID, MSGID (3 bytes,
 * little-endian), the payload, then the checksum (little-endian): X.25 over every byte after the start
 * marker, followed by the message's CRC_EXTRA ([MavlinkCrc]).
 *
 * The payload is truncated of its trailing zero bytes, which "MAVLink 2 implementations must" do; the
 * first byte is always kept, since "The first byte of the payload is never truncated, even if the payload
 * consists entirely of zeros" (serialization.html, Empty-Byte Payload Truncation). Signing arrives in v0.6.
 *
 * Arguments out of range throw [IllegalArgumentException]: they are caller bugs, never network input,
 * and masking them would send a frame nobody asked for. A message without CRC_EXTRA in [MavlinkMessages]
 * throws [IllegalStateException]; it cannot happen for the messages [encode] accepts.
 */
// One encode and one payload builder per message, so each wire layout can be reviewed on its own.
@Suppress("TooManyFunctions")
object MavlinkEncoder {

    private const val START_MARKER = 0xFD.toByte()
    private const val HEADER_LENGTH = 10
    private const val CHECKSUM_LENGTH = 2
    private const val LEN_INDEX = 1
    private const val INCOMPAT_FLAGS_INDEX = 2
    private const val COMPAT_FLAGS_INDEX = 3
    private const val SEQ_INDEX = 4
    private const val SYSID_INDEX = 5
    private const val COMPID_INDEX = 6
    private const val MSGID_INDEX = 7
    private const val MSGID_BYTES = 3

    /** The checksum covers everything after the start marker. */
    private const val CHECKSUM_OFFSET = 1
    private const val BYTE_MASK = 0xFF
    private const val UINT32_MAX = 0xFFFF_FFFFL

    /** 0 is the broadcast address (MAV_COMP_ID_ALL, and system 0 for "all systems"): never a sender. */
    private val SENDER_IDS = 1..BYTE_MASK
    private val UINT8_RANGE = 0..BYTE_MASK

    private const val HEARTBEAT_ID = 0

    // HEARTBEAT wire layout, as in MavlinkDecoder: custom_mode (uint32) first, then the five uint8 fields.
    private const val HEARTBEAT_LENGTH = 9
    private const val CUSTOM_MODE_OFFSET = 0
    private const val TYPE_OFFSET = 4
    private const val AUTOPILOT_OFFSET = 5
    private const val BASE_MODE_OFFSET = 6
    private const val SYSTEM_STATUS_OFFSET = 7
    private const val MAVLINK_VERSION_OFFSET = 8
    private const val UINT32_BYTES = 4

    /**
     * `mavlink_version` is "not writable by user, gets added by protocol because of magic data type:
     * uint8_t_mavlink_version" (`minimal.xml`); for MAVLink 2 it is 3, as the reference C library writes.
     */
    private const val MAVLINK_VERSION = 3

    private const val COMMAND_LONG_ID = 76

    // COMMAND_LONG wire layout: param1..param7 as seven consecutive floats first, then command (uint16),
    // then the three uint8 fields. No extensions.
    private const val COMMAND_LONG_LENGTH = 33
    private const val PARAM1_OFFSET = 0
    private const val COMMAND_OFFSET = 28
    private const val TARGET_SYSTEM_OFFSET = 30
    private const val TARGET_COMPONENT_OFFSET = 31
    private const val CONFIRMATION_OFFSET = 32
    private const val FLOAT_BYTES = 4
    private const val UINT16_BYTES = 2
    private val UINT16_RANGE = 0..0xFFFF

    private const val PARAM_REQUEST_READ_ID = 20

    // PARAM_REQUEST_READ wire layout: param_index (int16) first, then the two uint8 targets, then param_id
    // (char[16]). No extensions.
    private const val PARAM_REQUEST_READ_LENGTH = 20
    private const val PARAM_INDEX_OFFSET = 0
    private const val PARAM_TARGET_SYSTEM_OFFSET = 2
    private const val PARAM_TARGET_COMPONENT_OFFSET = 3
    private const val PARAM_ID_OFFSET = 4

    // "Send -1 to use the param ID field as identifier (else the param id will be ignored)" (`common.xml`).
    private const val PARAM_INDEX_BY_NAME = -1
    private const val INT16_BYTES = 2

    private const val MISSION_REQUEST_LIST_ID = 43
    private const val MISSION_COUNT_ID = 44
    private const val MISSION_ACK_ID = 47
    private const val MISSION_REQUEST_INT_ID = 51
    private const val MISSION_ITEM_INT_ID = 73

    // MISSION_REQUEST_LIST: target_system, target_component, then the extension mission_type.
    private const val REQUEST_LIST_LENGTH = 3
    private const val REQUEST_LIST_TARGET_SYSTEM_OFFSET = 0
    private const val REQUEST_LIST_TARGET_COMPONENT_OFFSET = 1
    private const val REQUEST_LIST_MISSION_TYPE_OFFSET = 2

    // MISSION_COUNT: count (uint16) first, the targets, then the extensions mission_type and opaque_id (uint32), in
    // XML order.
    private const val COUNT_LENGTH = 9
    private const val COUNT_OFFSET = 0
    private const val COUNT_TARGET_SYSTEM_OFFSET = 2
    private const val COUNT_TARGET_COMPONENT_OFFSET = 3
    private const val COUNT_MISSION_TYPE_OFFSET = 4
    private const val COUNT_OPAQUE_ID_OFFSET = 5

    // MISSION_ACK: the targets and type, then the extensions mission_type and opaque_id (uint32), in XML order.
    private const val MISSION_ACK_LENGTH = 8
    private const val MISSION_ACK_TARGET_SYSTEM_OFFSET = 0
    private const val MISSION_ACK_TARGET_COMPONENT_OFFSET = 1
    private const val MISSION_ACK_TYPE_OFFSET = 2
    private const val MISSION_ACK_MISSION_TYPE_OFFSET = 3
    private const val MISSION_ACK_OPAQUE_ID_OFFSET = 4

    // MISSION_REQUEST_INT: seq (uint16) first, the targets, then the extension mission_type.
    private const val REQUEST_INT_LENGTH = 5
    private const val REQUEST_INT_SEQ_OFFSET = 0
    private const val REQUEST_INT_TARGET_SYSTEM_OFFSET = 2
    private const val REQUEST_INT_TARGET_COMPONENT_OFFSET = 3
    private const val REQUEST_INT_MISSION_TYPE_OFFSET = 4

    // MISSION_ITEM_INT: param1..param4 (float), x and y (int32), z (float), seq and command (uint16), the six uint8
    // fields in XML order, then the extension mission_type.
    private const val ITEM_LENGTH = 38
    private const val ITEM_PARAM1_OFFSET = 0
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
    private const val INT32_BYTES = 4

    /**
     * Encodes [heartbeat] as one frame from [systemId]/[componentId] with sequence number [sequence].
     *
     * Its `mavlinkVersion` must be 3: the protocol sets that field, not the caller. HEARTBEAT therefore
     * never truncates, since its last byte on the wire is that 3.
     *
     * @param sequence 0..255; the sending component increments it for every message it sends and wraps
     * it after 255.
     * @param systemId 1..255.
     * @param componentId 1..255.
     */
    fun encode(sequence: Int, systemId: Int, componentId: Int, heartbeat: Heartbeat): ByteArray {
        require(heartbeat.mavlinkVersion == MAVLINK_VERSION) {
            "mavlink_version ${heartbeat.mavlinkVersion} is not $MAVLINK_VERSION; the protocol sets it, not the caller"
        }
        return frame(sequence, systemId, componentId, HEARTBEAT_ID, heartbeatPayload(heartbeat))
    }

    /**
     * Encodes [commandLong] as one frame from [systemId]/[componentId] with sequence number [sequence].
     *
     * The params are written as their IEEE-754 bits, little-endian, so a NaN is sent as given. A trailing
     * `confirmation` 0 is truncated like any trailing zero byte.
     *
     * @param sequence 0..255, as for HEARTBEAT.
     * @param systemId 1..255.
     * @param componentId 1..255.
     */
    fun encode(sequence: Int, systemId: Int, componentId: Int, commandLong: CommandLong): ByteArray =
        frame(sequence, systemId, componentId, COMMAND_LONG_ID, commandLongPayload(commandLong))

    /**
     * Encodes [request] as one frame from [systemId]/[componentId] with sequence number [sequence], with
     * `param_index` -1 so that the vehicle reads the parameter by name.
     *
     * The name's ASCII bytes are NUL-padded to 16; a 16-character name has no NUL. The padding is truncated like any
     * trailing zero byte.
     *
     * @param sequence 0..255, as for HEARTBEAT.
     * @param systemId 1..255.
     * @param componentId 1..255.
     */
    fun encode(sequence: Int, systemId: Int, componentId: Int, request: ParamRequestRead): ByteArray =
        frame(sequence, systemId, componentId, PARAM_REQUEST_READ_ID, paramRequestReadPayload(request))

    /**
     * Encodes [request] as one frame from [systemId]/[componentId] with sequence number [sequence]. A trailing
     * `mission_type` 0 (mission) is truncated like any trailing zero byte.
     *
     * @param sequence 0..255, as for HEARTBEAT.
     * @param systemId 1..255.
     * @param componentId 1..255.
     */
    fun encode(sequence: Int, systemId: Int, componentId: Int, request: MissionRequestList): ByteArray =
        frame(sequence, systemId, componentId, MISSION_REQUEST_LIST_ID, missionRequestListPayload(request))

    /** Encodes [count] as [encode] does a [MissionRequestList]; `opaque_id` is a uint32. */
    fun encode(sequence: Int, systemId: Int, componentId: Int, count: MissionCount): ByteArray =
        frame(sequence, systemId, componentId, MISSION_COUNT_ID, missionCountPayload(count))

    /** Encodes [ack] as [encode] does a [MissionRequestList]; `opaque_id` is a uint32. */
    fun encode(sequence: Int, systemId: Int, componentId: Int, ack: MissionAck): ByteArray =
        frame(sequence, systemId, componentId, MISSION_ACK_ID, missionAckPayload(ack))

    /** Encodes [request] as [encode] does a [MissionRequestList]. */
    fun encode(sequence: Int, systemId: Int, componentId: Int, request: MissionRequestInt): ByteArray =
        frame(sequence, systemId, componentId, MISSION_REQUEST_INT_ID, missionRequestIntPayload(request))

    /**
     * Encodes [item] as [encode] does a [MissionRequestList]. The params and `z` are written as their IEEE-754 bits,
     * so a NaN ("unchanged" for many commands) is sent as given.
     */
    fun encode(sequence: Int, systemId: Int, componentId: Int, item: MissionItemInt): ByteArray =
        frame(sequence, systemId, componentId, MISSION_ITEM_INT_ID, missionItemIntPayload(item))

    private fun heartbeatPayload(heartbeat: Heartbeat): ByteArray {
        val payload = ByteArray(HEARTBEAT_LENGTH)
        payload.putUint32(CUSTOM_MODE_OFFSET, heartbeat.customMode, "custom_mode")
        payload.putUint8(TYPE_OFFSET, heartbeat.type, "type")
        payload.putUint8(AUTOPILOT_OFFSET, heartbeat.autopilot, "autopilot")
        payload.putUint8(BASE_MODE_OFFSET, heartbeat.baseMode, "base_mode")
        payload.putUint8(SYSTEM_STATUS_OFFSET, heartbeat.systemStatus, "system_status")
        payload.putUint8(MAVLINK_VERSION_OFFSET, heartbeat.mavlinkVersion, "mavlink_version")
        return payload
    }

    private fun commandLongPayload(command: CommandLong): ByteArray {
        val payload = ByteArray(COMMAND_LONG_LENGTH)
        val params = floatArrayOf(
            command.param1,
            command.param2,
            command.param3,
            command.param4,
            command.param5,
            command.param6,
            command.param7,
        )
        params.forEachIndexed { index, param ->
            // Any float is valid, NaN and infinities included: its raw IEEE-754 bits go on the wire unchanged.
            payload.putLittleEndian(PARAM1_OFFSET + index * FLOAT_BYTES, param.toRawBits().toLong(), FLOAT_BYTES)
        }
        payload.putUint16(COMMAND_OFFSET, command.command, "command")
        payload.putUint8(TARGET_SYSTEM_OFFSET, command.targetSystem, "target_system")
        payload.putUint8(TARGET_COMPONENT_OFFSET, command.targetComponent, "target_component")
        payload.putUint8(CONFIRMATION_OFFSET, command.confirmation, "confirmation")
        return payload
    }

    private fun paramRequestReadPayload(request: ParamRequestRead): ByteArray {
        val payload = ByteArray(PARAM_REQUEST_READ_LENGTH)
        payload.putLittleEndian(PARAM_INDEX_OFFSET, PARAM_INDEX_BY_NAME.toLong(), INT16_BYTES)
        payload.putUint8(PARAM_TARGET_SYSTEM_OFFSET, request.targetSystem, "target_system")
        payload.putUint8(PARAM_TARGET_COMPONENT_OFFSET, request.targetComponent, "target_component")
        // ParamRequestRead holds only ASCII, so each character is one byte; the zeroed array is the NUL padding.
        request.paramId.forEachIndexed { index, character ->
            payload[PARAM_ID_OFFSET + index] = character.code.toByte()
        }
        return payload
    }

    private fun missionRequestListPayload(request: MissionRequestList): ByteArray {
        val payload = ByteArray(REQUEST_LIST_LENGTH)
        payload.putUint8(REQUEST_LIST_TARGET_SYSTEM_OFFSET, request.targetSystem, "target_system")
        payload.putUint8(REQUEST_LIST_TARGET_COMPONENT_OFFSET, request.targetComponent, "target_component")
        payload.putUint8(REQUEST_LIST_MISSION_TYPE_OFFSET, request.missionType, "mission_type")
        return payload
    }

    private fun missionCountPayload(count: MissionCount): ByteArray {
        val payload = ByteArray(COUNT_LENGTH)
        payload.putUint16(COUNT_OFFSET, count.count, "count")
        payload.putUint8(COUNT_TARGET_SYSTEM_OFFSET, count.targetSystem, "target_system")
        payload.putUint8(COUNT_TARGET_COMPONENT_OFFSET, count.targetComponent, "target_component")
        payload.putUint8(COUNT_MISSION_TYPE_OFFSET, count.missionType, "mission_type")
        payload.putUint32(COUNT_OPAQUE_ID_OFFSET, count.opaqueId, "opaque_id")
        return payload
    }

    private fun missionAckPayload(ack: MissionAck): ByteArray {
        val payload = ByteArray(MISSION_ACK_LENGTH)
        payload.putUint8(MISSION_ACK_TARGET_SYSTEM_OFFSET, ack.targetSystem, "target_system")
        payload.putUint8(MISSION_ACK_TARGET_COMPONENT_OFFSET, ack.targetComponent, "target_component")
        payload.putUint8(MISSION_ACK_TYPE_OFFSET, ack.type, "type")
        payload.putUint8(MISSION_ACK_MISSION_TYPE_OFFSET, ack.missionType, "mission_type")
        payload.putUint32(MISSION_ACK_OPAQUE_ID_OFFSET, ack.opaqueId, "opaque_id")
        return payload
    }

    private fun missionRequestIntPayload(request: MissionRequestInt): ByteArray {
        val payload = ByteArray(REQUEST_INT_LENGTH)
        payload.putUint16(REQUEST_INT_SEQ_OFFSET, request.seq, "seq")
        payload.putUint8(REQUEST_INT_TARGET_SYSTEM_OFFSET, request.targetSystem, "target_system")
        payload.putUint8(REQUEST_INT_TARGET_COMPONENT_OFFSET, request.targetComponent, "target_component")
        payload.putUint8(REQUEST_INT_MISSION_TYPE_OFFSET, request.missionType, "mission_type")
        return payload
    }

    private fun missionItemIntPayload(item: MissionItemInt): ByteArray {
        val payload = ByteArray(ITEM_LENGTH)
        floatArrayOf(item.param1, item.param2, item.param3, item.param4).forEachIndexed { index, param ->
            payload.putFloat(ITEM_PARAM1_OFFSET + index * FLOAT_BYTES, param)
        }
        payload.putLittleEndian(ITEM_X_OFFSET, item.x.toLong(), INT32_BYTES)
        payload.putLittleEndian(ITEM_Y_OFFSET, item.y.toLong(), INT32_BYTES)
        payload.putFloat(ITEM_Z_OFFSET, item.z)
        payload.putUint16(ITEM_SEQ_OFFSET, item.seq, "seq")
        payload.putUint16(ITEM_COMMAND_OFFSET, item.command, "command")
        payload.putUint8(ITEM_TARGET_SYSTEM_OFFSET, item.targetSystem, "target_system")
        payload.putUint8(ITEM_TARGET_COMPONENT_OFFSET, item.targetComponent, "target_component")
        payload.putUint8(ITEM_FRAME_OFFSET, item.frame, "frame")
        payload.putUint8(ITEM_CURRENT_OFFSET, item.current, "current")
        payload.putUint8(ITEM_AUTOCONTINUE_OFFSET, item.autocontinue, "autocontinue")
        payload.putUint8(ITEM_MISSION_TYPE_OFFSET, item.missionType, "mission_type")
        return payload
    }

    /**
     * Frames a full-length [payload] of [messageId]: header, the payload without its trailing zero bytes,
     * checksum. Internal so that framing can be tested with payloads no real message produces.
     */
    internal fun frame(sequence: Int, systemId: Int, componentId: Int, messageId: Int, payload: ByteArray): ByteArray {
        require(sequence in UINT8_RANGE) { "Sequence $sequence is outside $UINT8_RANGE" }
        require(systemId in SENDER_IDS) { "System ID $systemId is outside $SENDER_IDS" }
        require(componentId in SENDER_IDS) { "Component ID $componentId is outside $SENDER_IDS" }
        val crcExtra = checkNotNull(MavlinkMessages.crcExtraFor(messageId)) { "No CRC_EXTRA for message $messageId" }
        val length = truncatedLength(payload)
        val frame = ByteArray(HEADER_LENGTH + length + CHECKSUM_LENGTH)
        frame[0] = START_MARKER
        frame[LEN_INDEX] = length.toByte()
        frame[INCOMPAT_FLAGS_INDEX] = 0
        frame[COMPAT_FLAGS_INDEX] = 0
        frame[SEQ_INDEX] = sequence.toByte()
        frame[SYSID_INDEX] = systemId.toByte()
        frame[COMPID_INDEX] = componentId.toByte()
        frame.putLittleEndian(MSGID_INDEX, messageId.toLong(), MSGID_BYTES)
        payload.copyInto(frame, destinationOffset = HEADER_LENGTH, startIndex = 0, endIndex = length)
        val checksumIndex = HEADER_LENGTH + length
        val checksum = MavlinkCrc.compute(frame, CHECKSUM_OFFSET, checksumIndex - CHECKSUM_OFFSET, crcExtra)
        frame.putLittleEndian(checksumIndex, checksum.toLong(), CHECKSUM_LENGTH)
        return frame
    }

    /** Payload length without its trailing zero bytes, never below one byte. */
    private fun truncatedLength(payload: ByteArray): Int {
        var length = payload.size
        while (length > 1 && payload[length - 1] == 0.toByte()) length--
        return length
    }

    private fun ByteArray.putUint8(offset: Int, value: Int, field: String) {
        require(value in UINT8_RANGE) { "$field $value is outside $UINT8_RANGE" }
        this[offset] = value.toByte()
    }

    private fun ByteArray.putUint16(offset: Int, value: Int, field: String) {
        require(value in UINT16_RANGE) { "$field $value is outside $UINT16_RANGE" }
        putLittleEndian(offset, value.toLong(), UINT16_BYTES)
    }

    private fun ByteArray.putUint32(offset: Int, value: Long, field: String) {
        require(value in 0..UINT32_MAX) { "$field $value is outside 0..$UINT32_MAX" }
        putLittleEndian(offset, value, UINT32_BYTES)
    }

    /** Any float is valid, NaN and infinities included: its raw IEEE-754 bits go on the wire unchanged. */
    private fun ByteArray.putFloat(offset: Int, value: Float) {
        putLittleEndian(offset, value.toRawBits().toLong(), FLOAT_BYTES)
    }

    /** The low [bytes] bytes of [value], least significant first. */
    private fun ByteArray.putLittleEndian(offset: Int, value: Long, bytes: Int) {
        for (index in 0 until bytes) {
            this[offset + index] = (value ushr (index * Byte.SIZE_BITS)).toByte()
        }
    }
}
