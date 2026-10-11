package com.owindev.gcs.feature.hud

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-017")
class ElapsedTimeTest {

    @Test
    fun `GIVEN times under an hour WHEN formatting them THEN shows minutes and seconds`() {
        val elapsed = listOf(Duration.ZERO, 7.seconds, 59.seconds, 61.seconds, 59.minutes + 59.seconds)

        val result = elapsed.map(::formatElapsed)

        result shouldBeEqualTo listOf("00:00", "00:07", "00:59", "01:01", "59:59")
    }

    @Test
    fun `GIVEN an hour or more WHEN formatting it THEN shows hours too`() {
        val elapsed = listOf(1.hours, 1.hours + 2.minutes + 3.seconds, 12.hours)

        val result = elapsed.map(::formatElapsed)

        result shouldBeEqualTo listOf("1:00:00", "1:02:03", "12:00:00")
    }

    @Test
    fun `GIVEN a fraction of a second WHEN formatting it THEN is truncated, never rounded up`() {
        val elapsed = 7_999.milliseconds

        val result = formatElapsed(elapsed)

        result shouldBeEqualTo "00:07"
    }
}
