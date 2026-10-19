package com.owindev.gcs.core.mavlink

/**
 * One MAVLink 2 frame as [MavlinkParser.parseRaw] delimits it, with its exact bytes on the wire, signature included
 * (ADR 0006).
 */
// Not data classes: they hold arrays, so equality compares contents explicitly.
sealed interface RawFrame {

    val bytes: ByteArray

    /** A frame whose checksum matched: [frame] is what [MavlinkParser.parse] yields. */
    class Valid(val frame: MavlinkFrame, override val bytes: ByteArray) : RawFrame {

        override fun equals(other: Any?): Boolean =
            other is Valid && frame == other.frame && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = HASH_MULTIPLIER * frame.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Valid($frame, ${bytes.size} bytes)"
    }

    /**
     * A frame whose message ID has no CRC_EXTRA, delimited by its LEN and never checked; still counted in
     * [ParserStats.unknownMessageId]. It holds no [MavlinkFrame], so it can never be decoded or change vehicle state.
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
