package com.owindev.gcs.core.mavlink

/**
 * Writes .tlog records as pymavlink's `mavutil.py` does: the timestamp with its two low bits (pymavlink's link ID)
 * cleared, then exactly one frame, since a whole datagram after one timestamp breaks readers (ADR 0006). A negative
 * timestamp or bytes that are not exactly one MAVLink 1 or 2 frame are a caller bug: [IllegalArgumentException].
 */
object TlogWriter {

    private const val LOW_BITS = 0x3L
    private const val MAVLINK2_START = 0xFD.toByte()
    private const val INCOMPAT_FLAGS_INDEX = 2
    private const val MESSAGE_ID_INDEX = 7
    private const val CHECKSUM_LENGTH = 2
    private const val SIGNATURE_LENGTH = 13
    private const val SIGNED_FLAG = 0x01
    private const val BYTE_MASK = 0xFF

    // The checksum covers the header after the start marker, then the payload.
    private const val CHECKSUM_START = 1

    // common.xml. The codec does not decode it, so it is recognised by its ID alone.
    private const val SETUP_SIGNING_ID = 256

    /** The record for [frame] received or sent at [timestampMicros], µs since the Unix epoch. */
    fun record(timestampMicros: Long, frame: ByteArray): ByteArray {
        require(timestampMicros >= 0) { "A timestamp before the Unix epoch: $timestampMicros µs" }
        requireOneFrame(frame)
        val record = ByteArray(TIMESTAMP_LENGTH + frame.size)
        val stored = timestampMicros and LOW_BITS.inv()
        for (index in 0 until TIMESTAMP_LENGTH) {
            val shift = (TIMESTAMP_LENGTH - 1 - index) * Byte.SIZE_BITS
            record[index] = (stored shr shift).toByte()
        }
        frame.copyInto(record, destinationOffset = TIMESTAMP_LENGTH)
        return record
    }

    /**
     * [frame] as the signing guide's Logging rules (https://mavlink.io/en/guide/message_signing.html#logging) let it
     * into the log (ADR 0006): the same array if unsigned; if signed, a copy with the signed flag cleared, the
     * signature removed and the checksum recomputed with [crcExtraLookup]. SETUP_SIGNING, a signed frame whose message
     * ID has no CRC_EXTRA and MAVLink 1 are [LogFrame.LeftOut]. Bytes that are not exactly one frame:
     * [IllegalArgumentException].
     */
    fun loggable(frame: ByteArray, crcExtraLookup: CrcExtraLookup): LogFrame {
        requireOneFrame(frame)
        if (frame[0] != MAVLINK2_START) return LogFrame.Mavlink1
        val messageId = unsigned(frame, MESSAGE_ID_INDEX) or
            (unsigned(frame, MESSAGE_ID_INDEX + 1) shl Byte.SIZE_BITS) or
            (unsigned(frame, MESSAGE_ID_INDEX + 2) shl 2 * Byte.SIZE_BITS)
        val incompatFlags = unsigned(frame, INCOMPAT_FLAGS_INDEX)
        return when {
            messageId == SETUP_SIGNING_ID -> LogFrame.SetupSigning

            incompatFlags and SIGNED_FLAG == 0 -> LogFrame.Write(frame)

            else -> crcExtraLookup.crcExtraFor(messageId)
                ?.let { LogFrame.Write(withoutSignature(frame, incompatFlags, it)) }
                ?: LogFrame.SignedUnknownMessage
        }
    }

    private fun withoutSignature(signed: ByteArray, incompatFlags: Int, crcExtra: Int): ByteArray {
        val frame = signed.copyOf(signed.size - SIGNATURE_LENGTH)
        frame[INCOMPAT_FLAGS_INDEX] = (incompatFlags and SIGNED_FLAG.inv()).toByte()
        val checksumIndex = frame.size - CHECKSUM_LENGTH
        val checksum = MavlinkCrc.compute(frame, CHECKSUM_START, checksumIndex - CHECKSUM_START, crcExtra)
        frame[checksumIndex] = checksum.toByte()
        frame[checksumIndex + 1] = (checksum shr Byte.SIZE_BITS).toByte()
        return frame
    }

    private fun requireOneFrame(frame: ByteArray) {
        require(mavlinkFrameLength(frame, 0, frame.size) == frame.size) {
            "Not exactly one MAVLink frame: ${frame.size} bytes"
        }
    }

    private fun unsigned(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and BYTE_MASK
}
