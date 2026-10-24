package com.owindev.gcs.data.vehicle.replay

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The time of the whole pipeline (watchdog, staleness, alerts, HUD): the [device] clock on the live link, and during a
 * replay where the time was when the pipeline started following it, plus what its [ReplayClock] has played since
 * (REQ-037), so a paused replay ages nothing and a gap in the recording ages everything as the live link would.
 *
 * It never goes back nor runs ahead of the device: a replay starts where the pipeline's time is, even if its clock
 * played before (a replay started while nobody observed the link), and plays no faster than the device, so the live
 * time is never behind it when the replay ends, and a live age is never frozen afterwards. Reading the highest time
 * so far guards the rest, so an age is never negative.
 */
internal class PipelineClock(private val device: TimeSource.WithComparableMarks) :
    AbstractLongTimeSource(unit = DurationUnit.NANOSECONDS) {

    private val origin = device.markNow()
    private val replay = AtomicReference<Replay?>(null)
    private val highest = AtomicLong(Long.MIN_VALUE)

    fun newReplayClock(): ReplayClock = ReplayClock(device)

    /** Follows [clock] from where the pipeline's time is now; already following it, nothing changes. */
    fun startReplay(clock: ReplayClock) {
        if (replay.get()?.clock === clock) return
        val now = read()
        replay.set(Replay(startedAt = now - clock.position.inWholeNanoseconds, clock = clock))
    }

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
