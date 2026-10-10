package com.owindev.gcs.data.vehicle.di

import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/**
 * A monotonic clock that keeps counting while the device sleeps: `SystemClock.elapsedRealtimeNanos()`
 * (https://developer.android.com/reference/android/os/SystemClock).
 *
 * `TimeSource.Monotonic` is `System.nanoTime()`, which stops while the CPU is suspended. A vehicle's
 * age would then be understated by the whole sleep. Wall-clock time is not an option either: the user
 * or the network can change it.
 */
internal object ElapsedRealtimeTimeSource : AbstractLongTimeSource(unit = DurationUnit.NANOSECONDS) {

    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}
