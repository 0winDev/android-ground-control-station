package com.owindev.gcs.data.vehicle.replay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-037")
class ReplayClockTest {

    private val device = TestTimeSource()

    private lateinit var clock: ReplayClock

    @BeforeEach
    fun setUp() {
        clock = ReplayClock(device)
    }

    @Test
    fun `GIVEN a new clock WHEN time passes THEN stays at zero`() {
        device += PLAYED

        val result = clock.position

        result shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `GIVEN a playing clock WHEN time passes THEN advances at the same pace`() {
        clock.play()
        device += PLAYED

        val result = clock.position

        result shouldBeEqualTo PLAYED
    }

    @Test
    fun `GIVEN a clock paused after playing WHEN time passes THEN keeps the position it had`() {
        clock.play()
        device += PLAYED
        clock.pause()
        device += PAUSED

        val result = clock.position

        result shouldBeEqualTo PLAYED
    }

    @Test
    fun `GIVEN a clock paused and played again WHEN time passes THEN continues from where it stopped`() {
        clock.play()
        device += PLAYED
        clock.pause()
        device += PAUSED
        clock.play()
        device += PLAYED

        val result = clock.position

        result shouldBeEqualTo PLAYED * 2
    }

    @Test
    fun `GIVEN a playing clock WHEN played again THEN keeps its position`() {
        clock.play()
        device += PLAYED
        clock.play()
        device += PLAYED

        val result = clock.position

        result shouldBeEqualTo PLAYED * 2
    }

    @Test
    fun `GIVEN a playing clock WHEN reading whether it plays THEN is true`() = runTest {
        clock.play()

        val result = clock.playing.first()

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a paused clock WHEN reading whether it plays THEN is false`() = runTest {
        clock.play()
        clock.pause()

        val result = clock.playing.first()

        result shouldBeEqualTo false
    }
}

private val PLAYED = 2.seconds
private val PAUSED = 30.seconds
