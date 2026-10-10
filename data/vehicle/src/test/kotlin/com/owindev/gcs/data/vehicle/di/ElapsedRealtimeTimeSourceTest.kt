package com.owindev.gcs.data.vehicle.di

import android.os.SystemClock
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-017")
class ElapsedRealtimeTimeSourceTest {

    private var elapsedRealtimeNanos = START_NANOS

    private lateinit var timeSource: TimeSource.WithComparableMarks

    @BeforeEach
    fun setUp() {
        timeSource = ElapsedRealtimeTimeSource
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `GIVEN the device slept after a mark WHEN reading its age THEN includes the time asleep`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtimeNanos() } answers { elapsedRealtimeNanos }
        val mark = timeSource.markNow()
        elapsedRealtimeNanos += ASLEEP.inWholeNanoseconds

        val result = mark.elapsedNow()

        result shouldBeEqualTo ASLEEP
    }
}

private const val START_NANOS = 1_000_000_000L
private val ASLEEP = 30.seconds
