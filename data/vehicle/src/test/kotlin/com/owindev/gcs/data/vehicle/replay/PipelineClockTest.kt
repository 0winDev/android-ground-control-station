package com.owindev.gcs.data.vehicle.replay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class PipelineClockTest {

    private val device = TestTimeSource()

    private lateinit var clock: PipelineClock

    @BeforeEach
    fun setUp() {
        clock = PipelineClock(device)
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the live link WHEN time passes THEN ages a mark as the device does`() {
        val mark = clock.markNow()
        device += LIVE

        val result = mark.elapsedNow()

        result shouldBeEqualTo LIVE
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay that has not started playing WHEN time passes THEN ages nothing`() {
        clock.startReplay()
        val mark = clock.markNow()
        device += PAUSED

        val result = mark.elapsedNow()

        result shouldBeEqualTo Duration.ZERO
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay paused after playing WHEN time passes THEN ages a mark only by the time played`() {
        val replay = clock.startReplay()
        val mark = clock.markNow()
        replay.play()
        device += PLAYED
        replay.pause()
        device += PAUSED

        val result = mark.elapsedNow()

        result shouldBeEqualTo PLAYED
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a live mark WHEN a replay starts THEN its age goes on from where it was`() {
        val mark = clock.markNow()
        device += LIVE
        clock.startReplay()
        device += PAUSED

        val result = mark.elapsedNow()

        result shouldBeEqualTo LIVE
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay mark WHEN the replay ends THEN the live time is never behind it`() {
        val replay = clock.startReplay()
        replay.play()
        device += PLAYED
        val mark = clock.markNow()
        clock.endReplay()

        val result = mark.elapsedNow()

        result shouldBeEqualTo Duration.ZERO
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a paused replay that ended WHEN time passes THEN follows the device again`() {
        val replay = clock.startReplay()
        replay.play()
        device += PLAYED
        replay.pause()
        device += PAUSED
        clock.endReplay()
        val mark = clock.markNow()
        device += LIVE

        val result = mark.elapsedNow()

        result shouldBeEqualTo LIVE
    }
}

private val LIVE = 5.seconds
private val PLAYED = 2.seconds
private val PAUSED = 30.seconds
