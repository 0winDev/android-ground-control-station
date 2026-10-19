package com.owindev.gcs.core.mavlink

/**
 * Writes the records of a telemetry log (.tlog) as pymavlink does (`mavutil.py`): an 8-byte big-endian timestamp in
 * µs since the Unix epoch with its two low bits cleared, which pymavlink reads back as link 0, then exactly one
 * MAVLink frame (ADR 0006). One frame per record: a whole datagram after one timestamp would break readers at the
 * next record.
 *
 * Its callers are the GCS's own code, so a frame that is not exactly one MAVLink 1 or 2 frame, or a negative
 * timestamp, is a bug: rejected with [IllegalArgumentException], never written.
 */
object TlogWriter {

    private const val LOW_BITS = 0x3L

    /** The record for [frame] received or sent at [timestampMicros], µs since the Unix epoch. */
    fun record(timestampMicros: Long, frame: ByteArray): ByteArray {
        require(timestampMicros >= 0) { "A timestamp before the Unix epoch: $timestampMicros µs" }
        require(mavlinkFrameLength(frame, 0, frame.size) == frame.size) {
            "Not exactly one MAVLink frame: ${frame.size} bytes"
        }
        val record = ByteArray(TIMESTAMP_LENGTH + frame.size)
        val stored = timestampMicros and LOW_BITS.inv()
        for (index in 0 until TIMESTAMP_LENGTH) {
            val shift = (TIMESTAMP_LENGTH - 1 - index) * Byte.SIZE_BITS
            record[index] = (stored shr shift).toByte()
        }
        frame.copyInto(record, destinationOffset = TIMESTAMP_LENGTH)
        return record
    }
}
