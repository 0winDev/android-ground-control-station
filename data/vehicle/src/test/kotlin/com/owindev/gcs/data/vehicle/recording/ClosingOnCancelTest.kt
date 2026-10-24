package com.owindev.gcs.data.vehicle.recording

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Real threads: the read blocks one of them, as a stalled content provider does. */
@Tag("REQ-038")
class ClosingOnCancelTest {

    @Test
    fun `GIVEN a read blocked on a stream WHEN its caller is cancelled THEN the stream closes and the caller ends`() =
        runBlocking {
            val stream = StalledStream()
            val copy = launch(Dispatchers.IO) { stream.closingOnCancel { it.read() } }
            stream.reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            copy.cancel()
            withTimeout(TIMEOUT_SECONDS.seconds) { copy.join() }

            stream.closed shouldBeEqualTo true
        }

    @Test
    fun `GIVEN a block that ends WHEN it returns THEN the stream is closed`() = runBlocking {
        val stream = StalledStream()

        stream.closingOnCancel { }

        stream.closed shouldBeEqualTo true
    }

    /** A read that waits until the stream is closed, then fails as a closed descriptor does. */
    private class StalledStream : InputStream() {
        val reading = CountDownLatch(1)
        private val closing = CountDownLatch(1)

        @Volatile
        var closed = false

        override fun read(): Int {
            reading.countDown()
            closing.await()
            throw IOException("Closed while reading")
        }

        override fun close() {
            closed = true
            closing.countDown()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
