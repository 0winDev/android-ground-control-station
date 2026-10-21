package com.owindev.gcs.core.designsystem.time

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
    fun `GIVEN times under a minute WHEN splitting them THEN are seconds alone`() {
        val elapsed = listOf(Duration.ZERO, 7.seconds, 59.seconds)

        val result = elapsed.map(::elapsedPartsOf)

        result shouldBeEqualTo listOf(ElapsedParts(0, 0, 0), ElapsedParts(0, 0, 7), ElapsedParts(0, 0, 59))
    }

    @Test
    fun `GIVEN times past a minute WHEN splitting them THEN are minutes and seconds`() {
        val elapsed = listOf(1.minutes, 1.minutes + 46.seconds, 59.minutes + 59.seconds)

        val result = elapsed.map(::elapsedPartsOf)

        result shouldBeEqualTo listOf(ElapsedParts(0, 1, 0), ElapsedParts(0, 1, 46), ElapsedParts(0, 59, 59))
    }

    @Test
    fun `GIVEN an hour or more WHEN splitting it THEN shows hours too`() {
        val elapsed = listOf(1.hours, 1.hours + 2.minutes + 3.seconds, 30.hours)

        val result = elapsed.map(::elapsedPartsOf)

        result shouldBeEqualTo listOf(ElapsedParts(1, 0, 0), ElapsedParts(1, 2, 3), ElapsedParts(30, 0, 0))
    }

    @Test
    fun `GIVEN a fraction of a second WHEN splitting it THEN is truncated, never rounded up`() {
        val elapsed = 7_999.milliseconds

        val result = elapsedPartsOf(elapsed)

        result shouldBeEqualTo ElapsedParts(0, 0, 7)
    }
}
