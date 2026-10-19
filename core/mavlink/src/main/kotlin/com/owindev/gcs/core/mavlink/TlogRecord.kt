package com.owindev.gcs.core.mavlink

/**
 * One record of a telemetry log (.tlog): a timestamp, then one MAVLink frame (ADR 0006).
 *
 * [timestampMicros] is the 8-byte big-endian value as stored, in µs since the Unix epoch, low bits included:
 * pymavlink reads the two low bits as a link ID, and a writer that does not clear them (Mission Planner) leaves
 * them set. It is wall-clock time, so it can jump backwards or far forwards. A stored value of 2⁶³ or more reads
 * as a negative [Long]. [frame] is one MAVLink 1 or 2 frame exactly as stored, never checked: it is validated when
 * it is replayed through the parser.
 *
 * Not a data class: it holds an array, so equality compares its contents explicitly.
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
 * What a [TlogReader] has dropped so far, by reason. A damaged log is counted, never thrown (REQ-012).
 *
 * @property skippedBytes bytes discarded while looking for a record: garbage before or between records, a timestamp
 * without a frame in the middle of the log, a start marker after an implausible timestamp, the start of a false record
 * found by [TlogReader.finish], a tail at the end of the log before any anchor.
 * @property truncatedRecords records cut short by the end of the log after the first anchor, a lone timestamp at the
 * end included; counted by [TlogReader.finish].
 */
data class TlogStats(val skippedBytes: Long = 0, val truncatedRecords: Long = 0)
