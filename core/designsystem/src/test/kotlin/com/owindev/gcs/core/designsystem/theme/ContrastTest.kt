package com.owindev.gcs.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNear
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Contrast of every foreground the UI draws on its background, in both palettes, against WCAG 2.2: AA is 4.5:1 for
 * text and 3:1 for non-text parts, AAA is 7:1 for text (https://www.w3.org/TR/WCAG22/#contrast-minimum,
 * #contrast-enhanced, #non-text-contrast). The formula is the W3C's own definition of contrast ratio and relative
 * luminance (https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio).
 */
@Tag("REQ-030")
class ContrastTest {

    @Test
    fun `GIVEN black on white and a color on itself WHEN measuring the contrast THEN are 21 and 1`() {
        val pairs = listOf(Color.Black to Color.White, DayCyan to DayCyan)

        val result = pairs.map { (foreground, background) -> contrast(foreground, background) }

        result[0].shouldBeNear(MAXIMUM_CONTRAST, TOLERANCE)
        result[1].shouldBeNear(MINIMUM_CONTRAST, TOLERANCE)
    }

    @Test
    fun `GIVEN the night palette WHEN measuring every text on its background THEN none is under AA`() {
        val pairs = textPairs(NightColorScheme, NightGcsColors)

        val result = pairs.filterValues { (foreground, background) -> contrast(foreground, background) < AA_TEXT }

        result.keys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the day palette WHEN measuring every text on its background THEN none is under AA`() {
        val pairs = textPairs(DayColorScheme, DayGcsColors)

        val result = pairs.filterValues { (foreground, background) -> contrast(foreground, background) < AA_TEXT }

        result.keys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the day palette WHEN measuring the main text on the backgrounds THEN none is under AAA`() {
        val pairs = mapOf(
            "onBackground on background" to (DayColorScheme.onBackground to DayColorScheme.background),
            "onSurface on surface" to (DayColorScheme.onSurface to DayColorScheme.surface),
        )

        val result = pairs.filterValues { (foreground, background) -> contrast(foreground, background) < AAA_TEXT }

        result.keys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN both palettes WHEN measuring the parts that are not text THEN none is under 3 to 1`() {
        val pairs = nonTextPairs("night", NightColorScheme, NightGcsColors) +
            nonTextPairs("day", DayColorScheme, DayGcsColors)

        val result = pairs.filterValues { (foreground, background) -> contrast(foreground, background) < AA_NON_TEXT }

        result.keys.shouldBeEmpty()
    }

    @Tag("REQ-034")
    @Test
    fun `GIVEN the night map's backgrounds WHEN measuring the live vehicle THEN its fill or outline reaches 3 to 1`() {
        val backgrounds = FIORD_BACKGROUNDS

        val result = backgrounds.filterValues { background ->
            maxOf(
                contrast(NightColorScheme.primary, background),
                contrast(NightColorScheme.surface, background),
            ) < AA_NON_TEXT
        }

        result.keys.shouldBeEmpty()
    }

    @Tag("REQ-034")
    @Test
    fun `GIVEN the night map's backgrounds WHEN measuring the stale colour on them THEN some are under 3 to 1`() {
        val backgrounds = FIORD_BACKGROUNDS

        val result = backgrounds.filterValues { background -> contrast(NightGcsColors.stale, background) < AA_NON_TEXT }

        result.keys.shouldBeEqualTo(setOf("background", "wood", "park", "residential", "building"))
    }

    @Tag("REQ-034")
    @Test
    fun `GIVEN both palettes WHEN measuring the greyed vehicle on its disc THEN reaches 3 to 1`() {
        val pairs = listOf(
            NightGcsColors.stale to NightColorScheme.surface,
            DayGcsColors.stale to DayColorScheme.surface,
        )

        val result = pairs.map { (foreground, background) -> contrast(foreground, background) >= AA_NON_TEXT }

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN both palettes WHEN comparing their semantic tokens THEN the day one defines every one the night does`() {
        val night = NightGcsColors

        val result = DayGcsColors::class.java.declaredFields.map { it.name }

        result shouldBeEqualTo night::class.java.declaredFields.map { it.name }
    }
}

/**
 * What the vehicle can be drawn on with OpenFreeMap's `fiord` style, the night map (ADR 0005), as read from
 * https://tiles.openfreemap.org/styles/fiord on 2026-10-21: the background, water and aeroway as they are, and the
 * translucent fills over the background at their opacity at the follow zoom (wood 0.57 of hsl(232,18%,30%), park 0.3
 * of hsl(204,17%,35%), residential 0.6 of rgb(234,234,230), building 0.25 × 0.65 of hsl(232,47%,18%)).
 */
private val FIORD_BACKGROUNDS = mapOf(
    "background" to Color(0xFF45516E),
    "water" to Color(0xFF38435C),
    "aeroway" to Color(0xFF3B4359),
    "wood" to Color(0xFF424863),
    "park" to Color(0xFF46546C),
    "residential" to Color(0xFFA8ADB6),
    "building" to Color(0xFF3E4967),
)

private const val AA_TEXT = 4.5
private const val AAA_TEXT = 7.0
private const val AA_NON_TEXT = 3.0
private const val MAXIMUM_CONTRAST = 21.0
private const val MINIMUM_CONTRAST = 1.0
private const val TOLERANCE = 0.01

// WCAG 2.2, relative luminance: sRGB channels linearised below and above 0.04045 (the W3C text says 0.03928, which
// gives the same result for 8-bit colors), weighted 0.2126 R, 0.7152 G, 0.0722 B.
private const val LINEAR_LIMIT = 0.04045
private const val LINEAR_DIVISOR = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_DIVISOR = 1.055
private const val GAMMA = 2.4
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val FLARE = 0.05

/** Every text the UI draws, named, with its foreground and background. */
private fun textPairs(scheme: ColorScheme, colors: GcsColors): Map<String, Pair<Color, Color>> = mapOf(
    "onBackground on background" to (scheme.onBackground to scheme.background),
    "onSurface on surface" to (scheme.onSurface to scheme.surface),
    "onSurface on surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
    "onSurfaceVariant on surface" to (scheme.onSurfaceVariant to scheme.surface),
    "onSurfaceVariant on surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
    "stale on surface" to (colors.stale to scheme.surface),
    "stale on surfaceContainer" to (colors.stale to scheme.surfaceContainer),
    "primary on surface" to (scheme.primary to scheme.surface),
    "onPrimary on primary" to (scheme.onPrimary to scheme.primary),
    "caution text on background" to (colors.caution to scheme.background),
    "caution text on surfaceContainer" to (colors.caution to scheme.surfaceContainer),
    "emergency text on background" to (colors.emergency to scheme.background),
    "nominal on surfaceContainer" to (colors.nominal to scheme.surfaceContainer),
    "advisory banner" to (colors.advisory to colors.advisoryContainer),
    "caution banner" to (colors.onCaution to colors.caution),
    "emergency banner" to (colors.onEmergencyContainer to colors.emergencyContainer),
)

/** The emergency banner's outline and the controls' outline, named with their [palette]. */
private fun nonTextPairs(palette: String, scheme: ColorScheme, colors: GcsColors): Map<String, Pair<Color, Color>> =
    mapOf(
        "$palette emergency outline" to (colors.emergency to colors.emergencyContainer),
        "$palette outline on surface" to (scheme.outline to scheme.surface),
        "$palette outline on surfaceContainer" to (scheme.outline to scheme.surfaceContainer),
    )

private fun contrast(foreground: Color, background: Color): Double {
    val (lighter, darker) = listOf(luminance(foreground), luminance(background)).sortedDescending()
    return (lighter + FLARE) / (darker + FLARE)
}

private fun luminance(color: Color): Double =
    RED_WEIGHT * linear(color.red) + GREEN_WEIGHT * linear(color.green) + BLUE_WEIGHT * linear(color.blue)

private fun linear(channel: Float): Double {
    val value = channel.toDouble()
    return if (value <= LINEAR_LIMIT) value / LINEAR_DIVISOR else ((value + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
}
