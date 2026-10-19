package com.owindev.gcs.core.mavlink

/**
 * One MAVLink 2 frame as [MavlinkParser.parseRaw] delimits it, for the flight log (ADR 0006).
 *
 * [bytes] are the frame exactly as received: start marker, header, payload as sent (truncation kept), checksum
 * and, when signed, the 13-byte signature. Not data classes: they hold arrays, so equality compares their
 * contents explicitly.
 */
sealed interface RawFrame {

    /** The frame's exact bytes on the wire. */
    val bytes: ByteArray

    /** A frame whose checksum matched with its message's CRC_EXTRA: [frame] is what [MavlinkParser.parse] yields. */
    class Valid(val frame: MavlinkFrame, override val bytes: ByteArray) : RawFrame {

        override fun equals(other: Any?): Boolean =
            other is Valid && frame == other.frame && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = HASH_MULTIPLIER * frame.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Valid($frame, ${bytes.size} bytes)"
    }

    /**
     * A frame whose message ID has no CRC_EXTRA in the lookup: delimited by its LEN and never checked, so only its
     * length is trusted. It holds no [MavlinkFrame], so it can never be decoded or change vehicle state; it still
     * counts in [ParserStats.unknownMessageId].
     */
    class UnknownMessage(val messageId: Int, override val bytes: ByteArray) : RawFrame {

        override fun equals(other: Any?): Boolean =
            other is UnknownMessage && messageId == other.messageId && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = HASH_MULTIPLIER * messageId + bytes.contentHashCode()

        override fun toString(): String = "UnknownMessage(msgid=$messageId, ${bytes.size} bytes)"
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}
