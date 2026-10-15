package com.owindev.gcs.feature.hud

import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-029")
class DisplayRoundingTest {

    @Test
    fun `GIVEN small negative values WHEN shown with their decimals THEN are zero without a sign`() {
        val values = listOf(-0.013 to 1, -0.04 to 1, -0.4 to 0)

        val result = values.map { (value, decimals) -> shown(value, decimals).toString() }

        result shouldBeEqualTo listOf("0.0", "0.0", "0.0")
    }

    @Test
    fun `GIVEN values that do not round to zero WHEN shown with their decimals THEN keep their sign`() {
        val values = listOf(-0.05 to 1, 0.25 to 1, -2.6 to 0)

        val result = values.map { (value, decimals) -> shown(value, decimals) }

        result shouldBeEqualTo listOf(-0.1, 0.3, -3.0)
    }

    @Test
    fun `GIVEN ties and random values WHEN shown THEN String format prints the same digits as for the raw value`() {
        val random = Random(SEED)
        val values = TIES + List(RANDOM_VALUES) { random.nextDouble(-RANGE, RANGE) }

        val result = values.flatMap { value ->
            DECIMALS.map { decimals -> format(shown(value, decimals), decimals) to format(value, decimals) }
        }.filterNot { (shown, raw) -> shown == raw || (raw == "-$shown" && shown.all { it == '0' || it == '.' }) }

        result shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN headings around north WHEN shown in whole degrees THEN never 360`() {
        val headings = listOf(359.4, 359.5, 359.99, 0.4)

        val result = headings.map(::shownHeading)

        result shouldBeEqualTo listOf(359.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `GIVEN stale ages WHEN shown in whole seconds THEN never under-state and are never 0`() {
        val ages = listOf(500.milliseconds, 1.seconds, 1_001.milliseconds, 2_999.milliseconds)

        val result = ages.map(::shownAge)

        result shouldBeEqualTo listOf(1.seconds, 2.seconds, 2.seconds, 3.seconds)
    }

    private fun format(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
}

private const val SEED = 42
private const val RANDOM_VALUES = 1_000
private const val RANGE = 400.0

/** Halfway cases, where half-up and half-even rounding disagree, and doubles just off their decimal text. */
private val TIES = listOf(0.25, 2.675, -0.05, 0.15, 0.5, 1.5, 2.5, -2.5, 359.5)
private val DECIMALS = listOf(0, 1, 5)
