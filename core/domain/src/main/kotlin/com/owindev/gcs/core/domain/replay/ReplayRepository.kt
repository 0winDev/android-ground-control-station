package com.owindev.gcs.core.domain.replay

import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a flight recording back through the same pipeline as the live link (REQ-037). Implemented in `:data:vehicle`
 * and bound there with Hilt.
 *
 * A replay and the live link never run together: starting one closes the live session and its recording first, and
 * the live link opens again only after [stop]. A replay never transmits and is never recorded.
 */
interface ReplayRepository {

    /** What is being played, if anything. */
    val state: StateFlow<ReplayState>

    /**
     * The recorded time the replay is skipping now: set while it waits out a gap in the recording longer than it ever
     * waits between two records, `null` otherwise, paused or not.
     */
    val skippedGap: StateFlow<Duration?>

    /**
     * Starts playing [recording] from its beginning at 1×, and returns the state it led to: [ReplayState.Playing], or a
     * [ReplayState.Refused] or [ReplayState.Failed] that leaves the live link as it was.
     */
    fun play(recording: File): ReplayState

    fun pause()

    fun resume()

    /** Ends the replay, if any, and gives the link back to the live session. */
    fun stop()
}
