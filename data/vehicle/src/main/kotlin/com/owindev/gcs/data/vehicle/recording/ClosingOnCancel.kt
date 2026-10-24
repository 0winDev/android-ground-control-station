package com.owindev.gcs.data.vehicle.recording

import java.io.Closeable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block] and closes [resource] as soon as the caller is cancelled: a blocking read never sees cancellation, but
 * closing its stream from another thread wakes it with an [java.io.IOException]. It also closes [resource] when
 * [block] ends, so callers close nothing else.
 */
internal suspend fun <C : Closeable, T> C.closingOnCancel(block: suspend (C) -> T): T = coroutineScope {
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            close()
        }
    }
    try {
        block(this@closingOnCancel)
    } finally {
        closer.cancel()
    }
}
