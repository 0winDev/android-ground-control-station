package com.owindev.gcs.core.domain.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A flight log on disk, recorded or imported: [name] in its folder, [sizeBytes], and when it was last written. */
data class Recording(val name: String, val sizeBytes: Long, val lastModifiedMillis: Long)

/**
 * When a flight recording closes (REQ-036) and which recordings make room for a new one (REQ-039): a recording closes
 * after [closeAfterLinkLoss] without a HEARTBEAT from the active vehicle, and each time one opens or closes the oldest
 * are deleted until all fit in [maxTotalBytes], never the one being recorded nor the last one closed.
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

        /** REQ-039's limit, 256 MiB. */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
    }
}
