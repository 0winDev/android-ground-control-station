package com.owindev.gcs.core.domain.telemetry

import kotlin.time.TimeMark

/**
 * A telemetry [value] and when it was received (REQ-026), marked on arrival on a monotonic clock that keeps
 * counting while the device sleeps.
 *
 * Its age is `receivedAt.elapsedNow()`, read when it is needed, so a value nobody updates still gets older;
 * whether it is too old is decided by [StalenessRule].
 */
data class Timed<out T>(val value: T, val receivedAt: TimeMark)
