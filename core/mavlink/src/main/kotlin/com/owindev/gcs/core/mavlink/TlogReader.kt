package com.owindev.gcs.core.mavlink

import kotlin.math.abs

/**
 * Reads a telemetry log (.tlog) fed in chunks of any size into records: an 8-byte big-endian timestamp in µs since the
 * Unix epoch, then one MAVLink 1 or 2 frame delimited by its header, its checksum left to [MavlinkParser] on replay
 * (ADR 0006). It never throws: damage is counted in [stats] (REQ-012); one reader per log, not thread-safe.
 */
class TlogReader {

    private val buffer = ByteArray(MAX_RECORD_LENGTH)
    private var buffered = 0
    private var skippedBytes = 0L
    private var truncatedRecords = 0L

    // After damage, a one-byte resync can meet a start marker inside a real timestamp, so a record then counts only
    // within RESYNC_WINDOW_MICROS of the anchor, as pymavlink's scan_timestamp. Without checksums, one false record
    // taken from garbage must never anchor: only two consecutive records do.
    // Plain fields rather than Long?, so a record boxes nothing.
    private var anchor = 0L
    private var anchored = false
    private var previous = 0L
    private var follows = false
    private var resynchronising = false

    val stats: TlogStats
        get() = TlogStats(skippedBytes = skippedBytes, truncatedRecords = truncatedRecords)

    /** Feeds the next [chunk] of the log and returns the records it completes, in order. */
    fun read(chunk: ByteArray): List<TlogRecord> {
        var records: MutableList<TlogRecord>? = null
        for (byte in chunk) {
            buffer[buffered++] = byte
            records = extractRecords(records)
        }
        return records ?: emptyList()
    }

    /**
     * Ends the log and returns the records hidden in the buffered tail. A tail that starts plausibly and holds no
     * record counts as one truncated record; other bytes count as skipped.
     */
    fun finish(): List<TlogRecord> {
        var records: MutableList<TlogRecord>? = null
        // The tail may start with a false record hiding real ones, so it is scanned again one byte at a time.
        var cutShort = -1L
        while (buffered > 0) {
            if (cutShort < 0 && startsPlausibleRecord()) cutShort = 0
            if (cutShort >= 0) cutShort++ else skippedBytes++
            resynchronising = true
            follows = false
            consume(1)
            val skippedBefore = skippedBytes
            val rescued = extractRecords(null)
            if (rescued == null) {
                // Still part of the tail cut short; a false start just before it makes the split, not the total,
                // inexact.
                if (cutShort >= 0) {
                    cutShort += skippedBytes - skippedBefore
                    skippedBytes = skippedBefore
                }
                continue
            }
            records = (records ?: mutableListOf()).apply { addAll(rescued) }
            if (cutShort > 0) skippedBytes += cutShort
            cutShort = -1
        }
        if (cutShort > 0) truncatedRecords++
        return records ?: emptyList()
    }

    private fun extractRecords(found: MutableList<TlogRecord>?): MutableList<TlogRecord>? {
        var records = found
        while (buffered > TIMESTAMP_LENGTH) {
            val frameLength = mavlinkFrameLength(buffer, TIMESTAMP_LENGTH, buffered - TIMESTAMP_LENGTH)
            if (frameLength == NOT_A_FRAME || (resynchronising && !plausible(timestamp()))) {
                skippedBytes++
                resynchronising = true
                follows = false
                consume(1)
                continue
            }
            val recordLength = TIMESTAMP_LENGTH + frameLength
            if (frameLength == HEADER_INCOMPLETE || buffered < recordLength) return records
            val timestamp = timestamp()
            records = (records ?: mutableListOf()).apply {
                add(TlogRecord(timestamp, buffer.copyOfRange(TIMESTAMP_LENGTH, recordLength)))
            }
            if (follows && near(previous, timestamp)) {
                anchor = timestamp
                anchored = true
            }
            previous = timestamp
            follows = true
            resynchronising = false
            consume(recordLength)
        }
        return records
    }

    // Before the first anchor, a tail cannot be told from garbage.
    private fun startsPlausibleRecord(): Boolean = anchored &&
        buffered >= TIMESTAMP_LENGTH &&
        near(anchor, timestamp()) &&
        (buffered == TIMESTAMP_LENGTH || mavlinkFrameLength(buffer, TIMESTAMP_LENGTH, 1) != NOT_A_FRAME)

    private fun plausible(timestamp: Long): Boolean = !anchored || near(anchor, timestamp)

    // A stored value of 2⁶³ or more reads as negative: opposite signs are never near, so the subtraction cannot
    // overflow.
    private fun near(a: Long, b: Long): Boolean = (a < 0) == (b < 0) && abs(a - b) <= RESYNC_WINDOW_MICROS

    private fun timestamp(): Long {
        var value = 0L
        for (index in 0 until TIMESTAMP_LENGTH) {
            value = (value shl Byte.SIZE_BITS) or (buffer[index].toLong() and BYTE_MASK)
        }
        return value
    }

    private fun consume(count: Int) {
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = count, endIndex = buffered)
        buffered -= count
    }

    companion object {
        /**
         * How far from the anchor a record may be after damage: pymavlink's 3 days. A clock jump larger than that
         * inside damage rejects the rest of the log, as in pymavlink.
         */
        const val RESYNC_WINDOW_MICROS = 3L * 24 * 60 * 60 * 1_000_000

        private const val MAX_RECORD_LENGTH = TIMESTAMP_LENGTH + MAX_FRAME_LENGTH
        private const val BYTE_MASK = 0xFFL
    }
}

internal const val TIMESTAMP_LENGTH = 8

internal const val NOT_A_FRAME = -1

// Negative, so it is never a length: 0 would let TlogWriter accept an empty frame.
internal const val HEADER_INCOMPLETE = -2

private const val MAVLINK2_START = 0xFD
private const val MAVLINK1_START = 0xFE
private const val MAVLINK2_HEADER_LENGTH = 10
private const val MAVLINK1_HEADER_LENGTH = 6
private const val CHECKSUM_LENGTH = 2
private const val SIGNATURE_LENGTH = 13
private const val MAX_PAYLOAD_LENGTH = 255
private const val MAX_FRAME_LENGTH = MAVLINK2_HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CHECKSUM_LENGTH + SIGNATURE_LENGTH
private const val LEN_INDEX = 1
private const val INCOMPAT_FLAGS_INDEX = 2
private const val UNSIGNED_BYTE_MASK = 0xFF

// MAVLINK_IFLAG_SIGNED: the only incompatibility flag that changes a frame's length.
private const val SIGNED_FLAG = 0x01

// The frame length from its header alone (https://mavlink.io/en/guide/serialization.html).
internal fun mavlinkFrameLength(bytes: ByteArray, start: Int, available: Int): Int {
    if (available < 1) return HEADER_INCOMPLETE
    return when (bytes[start].toInt() and UNSIGNED_BYTE_MASK) {
        MAVLINK2_START -> if (available <= INCOMPAT_FLAGS_INDEX) {
            HEADER_INCOMPLETE
        } else {
            val signed = bytes[start + INCOMPAT_FLAGS_INDEX].toInt() and SIGNED_FLAG != 0
            MAVLINK2_HEADER_LENGTH + unsignedLen(bytes, start) + CHECKSUM_LENGTH + if (signed) SIGNATURE_LENGTH else 0
        }

        MAVLINK1_START -> if (available <= LEN_INDEX) {
            HEADER_INCOMPLETE
        } else {
            MAVLINK1_HEADER_LENGTH + unsignedLen(bytes, start) + CHECKSUM_LENGTH
        }

        else -> NOT_A_FRAME
    }
}

private fun unsignedLen(bytes: ByteArray, start: Int): Int = bytes[start + LEN_INDEX].toInt() and UNSIGNED_BYTE_MASK
