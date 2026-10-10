package com.owindev.gcs.core.mavlink

/**
 * Extracts MAVLink 2 frames from a stream of datagrams (https://mavlink.io/en/guide/serialization.html).
 *
 * Frames may be split across datagrams, several may share one, and noise may sit between them. Bad
 * input is dropped and counted in [stats], never thrown (REQ-012):
 * - an unsupported incompatibility flag, LEN 0 or a bad checksum means LEN cannot be trusted, so only
 *   the start marker is dropped and the bytes already buffered are scanned again for the next one.
 *   LEN 0 is invalid because "The first byte of the payload is never truncated, even if the payload
 *   consists entirely of zeros" (serialization.html, Empty-Byte Payload Truncation); the same page's
 *   "minimum packet length is 12 bytes for acknowledgment packets without payload" names no message;
 * - an unknown message ID cannot be validated, so the whole frame is skipped by its LEN, as the
 *   reference C library does; a `0xFD` inside its payload starts no false frame. The cost: a false
 *   `0xFD` in noise that reads as an unknown message skips up to one maximum frame unchecked, losing
 *   any real frame inside that span (those bytes count in [ParserStats.unknownMessageId], not in
 *   [ParserStats.skippedBytes]).
 *
 * MAVLink 1 frames are not parsed: their bytes are skipped while searching for a start marker. The
 * signature of a signed frame is extracted but not verified (v0.6).
 *
 * The buffer is fixed at the largest possible frame, so a corrupted LEN can neither grow memory nor
 * make the parser wait for more than one frame's worth of bytes. One parser per link, used from one
 * coroutine: it keeps state between calls and is not thread-safe.
 *
 * @param crcExtraLookup must return CRC_EXTRA values in 0..255 (they come from the codec's message table).
 */
class MavlinkParser(private val crcExtraLookup: CrcExtraLookup) {

    private val buffer = ByteArray(MAX_FRAME_LENGTH)
    private var buffered = 0
    private var badChecksum = 0L
    private var unknownMessageId = 0L
    private var unsupportedIncompatFlags = 0L
    private var skippedBytes = 0L
    private var emptyPayload = 0L

    /** What has been dropped so far. */
    val stats: ParserStats
        get() = ParserStats(
            badChecksum = badChecksum,
            unknownMessageId = unknownMessageId,
            unsupportedIncompatFlags = unsupportedIncompatFlags,
            skippedBytes = skippedBytes,
            emptyPayload = emptyPayload,
        )

    /** Feeds one datagram and returns the valid frames it completes, in order. */
    fun parse(datagram: ByteArray): List<MavlinkFrame> {
        var frames: MutableList<MavlinkFrame>? = null
        for (byte in datagram) {
            if (buffered == 0 && byte != START_MARKER) {
                skippedBytes++
                continue
            }
            buffer[buffered++] = byte
            frames = extractFrames(frames)
        }
        return frames ?: emptyList()
    }

    /**
     * Consumes every complete frame at the start of the buffer; stops when more bytes are needed.
     * Returns [found] plus the new frames; the list is created on the first valid frame, so datagrams
     * without one allocate nothing.
     */
    private fun extractFrames(found: MutableList<MavlinkFrame>?): MutableList<MavlinkFrame>? {
        var frames = found
        while (buffered >= HEADER_LENGTH) {
            val incompatFlags = unsigned(INCOMPAT_FLAGS_INDEX)
            val payloadLength = unsigned(LEN_INDEX)
            if (headerRejected(incompatFlags, payloadLength)) {
                dropStartMarker()
                continue
            }
            val signatureLength = if (incompatFlags and SIGNED_FLAG != 0) SIGNATURE_LENGTH else 0
            val frameLength = HEADER_LENGTH + payloadLength + CHECKSUM_LENGTH + signatureLength
            if (buffered < frameLength) return frames
            val crcExtra = crcExtraLookup.crcExtraFor(messageId())
            when {
                crcExtra == null -> {
                    unknownMessageId++
                    consume(frameLength)
                }

                !checksumMatches(payloadLength, crcExtra) -> {
                    badChecksum++
                    dropStartMarker()
                }

                else -> {
                    val valid = frame(payloadLength, incompatFlags, signatureLength)
                    frames = (frames ?: mutableListOf()).apply { add(valid) }
                    consume(frameLength)
                }
            }
        }
        return frames
    }

    /** Counts and reports a header that alone proves LEN cannot be trusted. */
    private fun headerRejected(incompatFlags: Int, payloadLength: Int): Boolean = when {
        incompatFlags and SIGNED_FLAG.inv() != 0 -> {
            unsupportedIncompatFlags++
            true
        }

        payloadLength == 0 -> {
            emptyPayload++
            true
        }

        else -> false
    }

    private fun checksumMatches(payloadLength: Int, crcExtra: Int): Boolean {
        val checksumIndex = HEADER_LENGTH + payloadLength
        val computed = MavlinkCrc.compute(buffer, CHECKSUM_START, checksumIndex - CHECKSUM_START, crcExtra)
        val received = unsigned(checksumIndex) or (unsigned(checksumIndex + 1) shl Byte.SIZE_BITS)
        return computed == received
    }

    private fun frame(payloadLength: Int, incompatFlags: Int, signatureLength: Int): MavlinkFrame {
        val payloadEnd = HEADER_LENGTH + payloadLength
        val signatureStart = payloadEnd + CHECKSUM_LENGTH
        val signature = when (signatureLength) {
            0 -> null
            else -> buffer.copyOfRange(signatureStart, signatureStart + signatureLength)
        }
        return MavlinkFrame(
            sequence = unsigned(SEQUENCE_INDEX),
            systemId = unsigned(SYSTEM_ID_INDEX),
            componentId = unsigned(COMPONENT_ID_INDEX),
            messageId = messageId(),
            incompatFlags = incompatFlags,
            compatFlags = unsigned(COMPAT_FLAGS_INDEX),
            payload = buffer.copyOfRange(HEADER_LENGTH, payloadEnd),
            signature = signature,
        )
    }

    /** The message ID is 3 bytes, little-endian. */
    private fun messageId(): Int = unsigned(MESSAGE_ID_INDEX) or
        (unsigned(MESSAGE_ID_INDEX + 1) shl Byte.SIZE_BITS) or
        (unsigned(MESSAGE_ID_INDEX + 2) shl 2 * Byte.SIZE_BITS)

    /** Drops a rejected frame's start marker and scans the rest of the buffer for the next one. */
    private fun dropStartMarker() {
        skippedBytes++
        consume(1)
    }

    /** Removes [count] bytes from the front, then any bytes before the next start marker (skipped). */
    private fun consume(count: Int) {
        var next = count
        while (next < buffered && buffer[next] != START_MARKER) next++
        skippedBytes += next - count
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = next, endIndex = buffered)
        buffered -= next
    }

    private fun unsigned(index: Int): Int = buffer[index].toInt() and BYTE_MASK

    private companion object {
        const val START_MARKER = 0xFD.toByte()
        const val HEADER_LENGTH = 10
        const val CHECKSUM_LENGTH = 2
        const val SIGNATURE_LENGTH = 13
        const val MAX_PAYLOAD_LENGTH = 255
        const val MAX_FRAME_LENGTH = HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CHECKSUM_LENGTH + SIGNATURE_LENGTH
        const val LEN_INDEX = 1
        const val INCOMPAT_FLAGS_INDEX = 2
        const val COMPAT_FLAGS_INDEX = 3
        const val SEQUENCE_INDEX = 4
        const val SYSTEM_ID_INDEX = 5
        const val COMPONENT_ID_INDEX = 6
        const val MESSAGE_ID_INDEX = 7

        // The checksum covers the header after the start marker, then the payload.
        const val CHECKSUM_START = 1

        // MAVLINK_IFLAG_SIGNED, the only incompatibility flag defined by MAVLink 2.
        const val SIGNED_FLAG = 0x01
        const val BYTE_MASK = 0xFF
    }
}
