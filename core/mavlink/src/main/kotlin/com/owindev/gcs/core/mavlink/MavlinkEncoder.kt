package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat

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

    /**
     * Frames a full-length [payload] of [messageId]: header, the payload without its trailing zero bytes,
     * checksum. Internal so that truncation can be tested before a message that really truncates exists.
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

    private fun ByteArray.putUint32(offset: Int, value: Long, field: String) {
        require(value in 0..UINT32_MAX) { "$field $value is outside 0..$UINT32_MAX" }
        putLittleEndian(offset, value, UINT32_BYTES)
    }

    /** The low [bytes] bytes of [value], least significant first. */
    private fun ByteArray.putLittleEndian(offset: Int, value: Long, bytes: Int) {
        for (index in 0 until bytes) {
            this[offset + index] = (value ushr (index * Byte.SIZE_BITS)).toByte()
        }
    }
}
