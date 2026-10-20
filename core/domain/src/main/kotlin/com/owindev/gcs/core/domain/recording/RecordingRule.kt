package com.owindev.gcs.core.domain.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A flight log on disk, recorded or imported: [name] in its folder, [sizeBytes], and when it was last written. */
data class Recording(val name: String, val sizeBytes: Long, val lastModifiedMillis: Long)

/**
 * When a flight recording closes (REQ-036) and which recordings make room for a new one (REQ-039): a recording closes
 * after [closeAfterLinkLoss] without a HEARTBEAT from the active vehicle, and all of them together stay within
 * [maxTotalBytes], the oldest deleted first and never the one being recorded.
 */
class RecordingRule(
    val closeAfterLinkLoss: Duration = DEFAULT_CLOSE_AFTER_LINK_LOSS,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    init {
        require(closeAfterLinkLoss > Duration.ZERO) { "The close timeout must be positive, was $closeAfterLinkLoss" }
        require(maxTotalBytes > 0) { "The recordings limit must be positive, was $maxTotalBytes bytes" }
    }

    /** Whether the recording closes [sinceLastHeartbeat] after the active vehicle's last HEARTBEAT (monotonic clock). */
    fun closesAfter(sinceLastHeartbeat: Duration): Boolean = sinceLastHeartbeat >= closeAfterLinkLoss

    /**
     * The [recordings] to delete, oldest first (by last write, then name), until the rest fit in [maxTotalBytes]. The
     * one named [current] is never among them, so it alone can stay over the limit.
     */
    fun toDelete(recordings: List<Recording>, current: String?): List<Recording> {
        var excess = recordings.sumOf { it.sizeBytes } - maxTotalBytes
        val candidates = recordings
            .filter { it.name != current }
            .sortedWith(compareBy({ it.lastModifiedMillis }, { it.name }))
            .iterator()
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

        /** REQ-039's limit, 256 MiB. */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
    }
}
