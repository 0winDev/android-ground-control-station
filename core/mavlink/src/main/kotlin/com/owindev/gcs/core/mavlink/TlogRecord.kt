package com.owindev.gcs.core.mavlink

/**
 * One .tlog record: [timestampMicros] exactly as stored, in µs since the Unix epoch (low bits pymavlink reads as a
 * link ID, wall-clock jumps and values of 2⁶³ or more, read as negative, included), and one MAVLink [frame] as
 * stored, unchecked until it is replayed (ADR 0006).
 */
class TlogRecord(val timestampMicros: Long, val frame: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is TlogRecord && timestampMicros == other.timestampMicros && frame.contentEquals(other.frame)

    override fun hashCode(): Int = HASH_MULTIPLIER * timestampMicros.hashCode() + frame.contentHashCode()

    override fun toString(): String = "TlogRecord(t=$timestampMicros µs, ${frame.size} bytes)"

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

/**
 * What a [TlogReader] has dropped: [skippedBytes] outside any record, and [truncatedRecords] cut short by the end of
 * the log once two consecutive records have anchored it (before that, a tail counts as skipped).
 */
data class TlogStats(val skippedBytes: Long = 0, val truncatedRecords: Long = 0)
