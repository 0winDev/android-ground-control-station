package com.owindev.gcs.data.vehicle.replay

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * How far a replay has played (REQ-037): its [position] advances at 1× on the [device] clock while playing and stands
 * still while paused, so a pause never ages anything. It starts paused at zero; one clock per replay.
 */
internal class ReplayClock(private val device: TimeSource.WithComparableMarks) {

    private val state = MutableStateFlow<State>(State.Paused(position = Duration.ZERO))

    /** The position now. */
    val position: Duration
        get() = state.value.position()

    /** Whether the position is advancing, starting with the current answer. */
    val playing: Flow<Boolean> = state.map { it is State.Playing }.distinctUntilChanged()

    fun play() = state.update { current ->
        if (current is State.Paused) State.Playing(from = current.position, since = device.markNow()) else current
    }

    fun pause() = state.update { current ->
        if (current is State.Playing) State.Paused(position = current.position()) else current
    }

    /**
     * Suspends until the clock plays and its position has reached [target], so a paused replay delivers nothing, not
     * even what was already due. The position is read again after each wait, since a pause may have come in the middle
     * of it.
     */
    suspend fun awaitPosition(target: Duration) {
        do {
            playing.first { it }
            delay(target - position)
        } while (position < target)
    }

    private fun State.position(): Duration = when (this) {
        is State.Paused -> position
        is State.Playing -> from + since.elapsedNow()
    }

    private sealed interface State {
        data class Paused(val position: Duration) : State

        data class Playing(val from: Duration, val since: ComparableTimeMark) : State
    }
}
