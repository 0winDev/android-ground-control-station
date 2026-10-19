package com.owindev.gcs.core.mavlink

import kotlin.math.abs

/**
 * Reads a telemetry log (.tlog), the format QGroundControl, Mission Planner and pymavlink write: for each frame, an
 * 8-byte big-endian timestamp in µs since the Unix epoch, then the frame (ADR 0006).
 *
 * Fed in chunks of any size, it returns each record it completes. A record holds one MAVLink 1 (`0xFE`) or MAVLink 2
 * (`0xFD`) frame, delimited by its header alone: LEN and, for MAVLink 2, the signed flag. Its checksum is never
 * checked here: the frame is validated when it is replayed through [MavlinkParser], which also skips MAVLink 1.
 *
 * A damaged log is counted in [stats], never thrown (REQ-012). When no start marker follows a timestamp, the reader
 * drops one byte and looks again, so garbage between records or a timestamp without a frame costs only those bytes,
 * counted in [TlogStats.skippedBytes].
 *
 * While it looks again, it takes a start marker for a record only if the 8 bytes before it are a timestamp within
 * [RESYNC_WINDOW_MICROS] (3 days) of an anchor, as pymavlink does (`mavlogfile.scan_timestamp`): the bytes it inspects
 * after a lone timestamp are the next record's timestamp, and one of them reading as a start marker would otherwise
 * make a false record that swallows real ones. Bytes dropped for an implausible timestamp count as skipped too. The
 * anchor is the timestamp of a record that came right after another one, nothing dropped between them, within 3 days
 * of it. A single record cannot anchor: without a checksum check (pymavlink checks it), a false record taken from
 * garbage would anchor a timestamp no real record is near, and every record after it would be rejected. Until the
 * first anchor, the window cannot apply, and the first start marker after a whole timestamp is taken; a log without
 * two consecutive records never anchors, so it never gets the window. Outside a resynchronisation, timestamps are
 * taken as stored, jumps included: a real clock jump between records is kept.
 *
 * The costs, as for the parser: garbage right after a record (nothing dropped yet, so not checked), garbage that
 * passes the window while resynchronising, or a start marker in garbage before the first anchor, is taken for a
 * record and rejected when replayed. Known limit, as in pymavlink: if the wall clock jumps by
 * more than 3 days inside a damaged stretch, the records after it are rejected to the end of the log. The buffer is
 * fixed at the largest record, so a corrupted LEN can neither grow memory nor hold the reader for more than one
 * record's worth of bytes. One reader per log: it keeps state between calls and is not thread-safe.
 */
class TlogReader {

    private val buffer = ByteArray(MAX_RECORD_LENGTH)
    private var buffered = 0
    private var skippedBytes = 0L
    private var truncatedRecords = 0L

    // The last anchored timestamp; plain fields rather than a Long?, so a record boxes nothing.
    private var anchor = 0L
    private var anchored = false

    // The last record's timestamp, and whether that record just ended with nothing dropped since.
    private var previous = 0L
    private var follows = false

    // Bytes were dropped since the last record: a start marker must come after a plausible timestamp.
    private var resynchronising = false

    /** What has been dropped so far. */
    val stats: TlogStats
        get() = TlogStats(skippedBytes = skippedBytes, truncatedRecords = truncatedRecords)

    /** Feeds the next [chunk] of the log and returns the records it completes, in order. */
    fun read(chunk: ByteArray): List<TlogRecord> {
        // Created on the first record, so chunks without one allocate nothing.
        var records: MutableList<TlogRecord>? = null
        for (byte in chunk) {
            buffer[buffered++] = byte
            records = extractRecords(records)
        }
        return records ?: emptyList()
    }

    /**
     * Ends the log and returns the records still found in the bytes left in the buffer. Those bytes waited for a
     * record that never completed: its start may have been false, hiding real records behind it, so they are scanned
     * again one byte at a time. A tail from a plausible record start to the end with no record inside is a record cut
     * short, a lone timestamp included, and counts in [TlogStats.truncatedRecords]; other bytes count in
     * [TlogStats.skippedBytes]. Before the first anchor nothing is plausible, so a tail is skipped bytes: it cannot be
     * told from garbage. If a false start comes before a real record cut short, the bytes between them count with the
     * truncated record: the counts are right, only their split between the two is not.
     */
    fun finish(): List<TlogRecord> {
        var records: MutableList<TlogRecord>? = null
        // Bytes dropped since a plausible record start: one truncated record, unless a record completes after it.
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
                // Nothing completed: what extraction dropped is still part of the tail cut short, if there is one.
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

    /** Consumes every complete record at the start of the buffer; returns [found] plus the new ones. */
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

    /**
     * Whether the buffer could start a record cut short by the end of the log: a whole timestamp near the anchor,
     * alone or followed by a start marker. Fewer bytes than a timestamp, or any before the first anchor, cannot be told
     * from garbage.
     */
    private fun startsPlausibleRecord(): Boolean = anchored &&
        buffered >= TIMESTAMP_LENGTH &&
        near(anchor, timestamp()) &&
        (buffered == TIMESTAMP_LENGTH || mavlinkFrameLength(buffer, TIMESTAMP_LENGTH, 1) != NOT_A_FRAME)

    /** Near the anchor; anything is, before the first one. */
    private fun plausible(timestamp: Long): Boolean = !anchored || near(anchor, timestamp)

    /**
     * Whether [a] and [b] are within [RESYNC_WINDOW_MICROS] of each other. Of opposite signs they never are (a stored
     * value of 2⁶³ or more reads as negative): that check comes first, so the subtraction cannot overflow.
     */
    private fun near(a: Long, b: Long): Boolean = (a < 0) == (b < 0) && abs(a - b) <= RESYNC_WINDOW_MICROS

    /** The big-endian timestamp at the start of the buffer, as stored. */
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
        /** How far from the anchor a timestamp may be while resynchronising: pymavlink's 3 days. */
        const val RESYNC_WINDOW_MICROS = 3L * 24 * 60 * 60 * 1_000_000

        private const val MAX_RECORD_LENGTH = TIMESTAMP_LENGTH + MAX_FRAME_LENGTH
        private const val BYTE_MASK = 0xFFL
    }
}

/** The length of a .tlog record's timestamp. */
internal const val TIMESTAMP_LENGTH = 8

/** [mavlinkFrameLength]: the bytes do not start with a MAVLink 1 or 2 start marker. */
internal const val NOT_A_FRAME = -1

/**
 * [mavlinkFrameLength]: more bytes are needed to read the header. Negative, like [NOT_A_FRAME], so it can never be
 * mistaken for a length: 0 would match an empty array.
 */
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

/**
 * The length of the MAVLink 1 or 2 frame at [start] of [bytes], read from its header alone, given [available] bytes
 * from there: [NOT_A_FRAME] without a start marker, [HEADER_INCOMPLETE] until LEN (and, for MAVLink 2, the
 * incompatibility flags) can be read. A MAVLink 2 frame is header + LEN + checksum, + 13 when signed; a MAVLink 1
 * frame is its 6-byte header + LEN + checksum (https://mavlink.io/en/guide/serialization.html).
 */
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
