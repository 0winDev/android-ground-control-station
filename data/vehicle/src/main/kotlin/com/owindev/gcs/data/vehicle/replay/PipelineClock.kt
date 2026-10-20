package com.owindev.gcs.data.vehicle.replay

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The time of the whole pipeline (watchdog, staleness, alerts, HUD): the [device] clock on the live link, and during a
 * replay the moment it started plus its [ReplayClock] position (REQ-037), so a paused replay ages nothing and a gap in
 * the recording ages everything as the live link would.
 *
 * It never goes back: a replay starts where the live time is and plays no faster than the device, so the live time is
 * never behind it when the replay ends. Reading the highest time so far guards that, so an age is never negative.
 */
internal class PipelineClock(private val device: TimeSource.WithComparableMarks) :
    AbstractLongTimeSource(unit = DurationUnit.NANOSECONDS) {

    private val origin = device.markNow()
    private val replay = AtomicReference<Replay?>(null)
    private val highest = AtomicLong(Long.MIN_VALUE)

    /** Starts a replay's time where the pipeline's is now, and returns its clock, paused. */
    fun startReplay(): ReplayClock {
        val clock = ReplayClock(device)
        replay.set(Replay(startedAt = read(), clock = clock))
        return clock
    }

    /** Back to the device clock. */
    fun endReplay() {
        replay.set(null)
    }

    override fun read(): Long {
        val current = replay.get()
        val reading = current?.let { it.startedAt + it.clock.position.inWholeNanoseconds }
            ?: origin.elapsedNow().inWholeNanoseconds
        return highest.updateAndGet { maxOf(it, reading) }
    }

    private class Replay(val startedAt: Long, val clock: ReplayClock)
}
