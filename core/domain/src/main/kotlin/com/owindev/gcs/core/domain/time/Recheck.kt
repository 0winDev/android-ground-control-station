package com.owindev.gcs.core.domain.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shortest wait before a time-based check runs again (REQ-037). While a replay is paused its time stands still, so
 * a wait worked out from it comes out the same each time; never shorter than this, a check wakes at most 20 times a
 * second then, and on the live link it runs at most this late.
 */
val MIN_RECHECK: Duration = 50.milliseconds

/** This wait, never shorter than [MIN_RECHECK]. */
fun Duration.atLeastRecheck(): Duration = maxOf(this, MIN_RECHECK)
