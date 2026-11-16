package com.owindev.gcs.core.domain.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A flight log on disk, recorded or imported: [name] in its folder, [sizeBytes], and when it was last written. */
data class Recording(val name: String, val sizeBytes: Long, val lastModifiedMillis: Long)

/**
 * When a flight recording closes (REQ-049) and which recordings make room for a new one (REQ-039): a recording closes
 * after [closeAfterLinkLoss] without a HEARTBEAT from the active vehicle, or once no live vehicle has been armed for
 * [closeAfterDisarmed] after one was, and each time one opens or closes the oldest are deleted until all fit in
 * [maxTotalBytes], never the one being recorded nor the last one closed.
 */
class RecordingRule(
    val closeAfterLinkLoss: Duration = DEFAULT_CLOSE_AFTER_LINK_LOSS,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val closeAfterDisarmed: Duration = DEFAULT_CLOSE_AFTER_DISARMED,
) {

    init {
        require(closeAfterLinkLoss > Duration.ZERO) { "The close timeout must be positive, was $closeAfterLinkLoss" }
        require(closeAfterDisarmed > Duration.ZERO) { "The disarm margin must be positive, was $closeAfterDisarmed" }
        require(maxTotalBytes > 0) { "The recordings limit must be positive, was $maxTotalBytes bytes" }
    }

    /** Whether the recording closes [sinceLastHeartbeat] after the active vehicle's last HEARTBEAT (monotonic clock). */
    fun closesAfter(sinceLastHeartbeat: Duration): Boolean = sinceLastHeartbeat >= closeAfterLinkLoss

    /** Whether the recording closes [sinceDisarmed] after the first HEARTBEAT showing no live vehicle armed. */
    fun closesAfterDisarmed(sinceDisarmed: Duration): Boolean = sinceDisarmed >= closeAfterDisarmed

    /**
     * The [recordings] to delete, oldest first (by last write, then name), until the rest fit in [maxTotalBytes]. Never
     * the one named [kept], the last one closed or the one closing, so it alone can stay over the limit; with no name,
     * the newest is kept, as the last one closed before the app started. Nor the one being [replayed] (REQ-037) nor the
     * one being recorded ([recording]), as when an import applies the limit (REQ-038); both still count in the total.
     */
    fun toDelete(recordings: List<Recording>, kept: String?, replayed: String?, recording: String?): List<Recording> {
        var excess = recordings.sumOf { it.sizeBytes } - maxTotalBytes
        val oldestFirst = recordings.sortedWith(compareBy({ it.lastModifiedMillis }, { it.name }))
        val keptName = kept ?: oldestFirst.lastOrNull()?.name
        val protected = setOf(keptName, replayed, recording)
        val candidates = oldestFirst.filter { it.name !in protected }.iterator()
        val deleted = mutableListOf<Recording>()
        while (excess > 0 && candidates.hasNext()) {
            val oldest = candidates.next()
            deleted += oldest
            excess -= oldest.sizeBytes
        }
        return deleted
    }

    companion object {
        /** Decided for v0.3: a link back within it continues the same flight's log. */
        val DEFAULT_CLOSE_AFTER_LINK_LOSS: Duration = 10.minutes

        /**
         * Three times ArduCopter's default `DISARM_DELAY` (10 s, `ArduCopter/Parameters.cpp`), after which it disarms
         * a copter armed on the ground that has not taken off: an operator who arms again after that, or after a short
         * stop, keeps one log, and the flight can be replayed half a minute after it disarms. Measured in SITL (#89):
         * disarmed 10.0 s after arming on the ground, 2.8 s after touchdown in LAND.
         */
        val DEFAULT_CLOSE_AFTER_DISARMED: Duration = 30.seconds

        /** REQ-039's limit, 256 MiB. */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
    }
}
