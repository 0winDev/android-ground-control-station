package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded

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
    private const val GLOBAL_POSITION_INT_ID = 33
    private const val VFR_HUD_ID = 74

    private const val COMMAND_ACK_ID = 77

    // COMMAND_ACK wire layout: command (uint16), result, then the extensions in XML order: progress,
    // result_param2 (int32), target_system, target_component.
    private const val ACK_COMMAND_OFFSET = 0
    private const val ACK_RESULT_OFFSET = 2
    private const val ACK_PROGRESS_OFFSET = 3
    private const val ACK_RESULT_PARAM2_OFFSET = 4
    private const val ACK_TARGET_SYSTEM_OFFSET = 8
    private const val ACK_TARGET_COMPONENT_OFFSET = 9

    private const val UINT16_BYTES = 2
    private const val INT32_BYTES = 4
    private const val UINT32_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val UINT32_MASK = 0xFFFF_FFFFL

    fun decode(frame: MavlinkFrame): MavlinkMessage {
        val fullLength = MavlinkMessages.fullLengthOf(frame.messageId)
            ?: return Undecoded(frame.messageId, frame.payload.copyOf())
        return when (frame.messageId) {
            HEARTBEAT_ID -> heartbeat(frame.payload)
            ATTITUDE_ID -> TODO("#37")
            GLOBAL_POSITION_INT_ID -> TODO("#37")
            VFR_HUD_ID -> TODO("#37")
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

    private fun commandAck(payload: ByteArray) = CommandAck(
        command = payload.uint16(ACK_COMMAND_OFFSET),
        result = payload.uint8(ACK_RESULT_OFFSET),
        progress = payload.uint8(ACK_PROGRESS_OFFSET),
        resultParam2 = payload.int32(ACK_RESULT_PARAM2_OFFSET),
        targetSystem = payload.uint8(ACK_TARGET_SYSTEM_OFFSET),
        targetComponent = payload.uint8(ACK_TARGET_COMPONENT_OFFSET),
    )

    /** A byte past the end of a truncated payload reads as zero, which restores it without copying. */
    private fun ByteArray.uint8(offset: Int): Int = if (offset < size) this[offset].toInt() and BYTE_MASK else 0

    private fun ByteArray.uint16(offset: Int): Int = littleEndian(offset, UINT16_BYTES)

    /** Little-endian int32: Kotlin's [Int] is the same two's-complement 32 bits, so the sign comes for free. */
    private fun ByteArray.int32(offset: Int): Int = littleEndian(offset, INT32_BYTES)

    /** Little-endian uint32, returned as a [Long] so values above `Int.MAX_VALUE` stay positive. */
    private fun ByteArray.uint32(offset: Int): Long = littleEndian(offset, UINT32_BYTES).toLong() and UINT32_MASK

    /** The [bytes] bytes at [offset], least significant first, as the low bits of an [Int]. */
    private fun ByteArray.littleEndian(offset: Int, bytes: Int): Int {
        var value = 0
        for (index in bytes - 1 downTo 0) {
            value = (value shl Byte.SIZE_BITS) or uint8(offset + index)
        }
        return value
    }
}
