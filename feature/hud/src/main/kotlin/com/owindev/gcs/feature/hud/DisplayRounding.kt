package com.owindev.gcs.feature.hud

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// How the HUD rounds what it shows, kept apart from Compose so it can be tested (REQ-029).

/**
 * [value] rounded to the [decimals] it is shown with, and 0 without a sign when it rounds to 0: -0.013 m is
 * "0.0 m".
 */
internal fun shown(value: Double, decimals: Int): Double {
    // Half up from the shortest decimal text of the double, as String.format rounds, so both agree on every digit.
    val rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    // -0.0 == 0.0 for doubles, so this also turns a negative zero into a positive one.
    return if (rounded == 0.0) 0.0 else rounded
}

/** A heading in whole degrees, 0 to 359: 359.5° rounds to north, shown as 0°, never 360°. */
internal fun shownHeading(degrees: Double): Double {
    val rounded = shown(degrees, decimals = 0)
    return if (rounded >= FULL_CIRCLE_DEGREES) rounded - FULL_CIRCLE_DEGREES else rounded
}

/**
 * The age shown for a stale value: the whole seconds passed plus one, so it never under-states the age and a value
 * stale at 0.5 s never reads "0 s ago", which would say it is current while only the grey says otherwise. It
 * changes exactly on each whole second, when the HUD judges the panel again (`untilPanelChanges`).
 */
internal fun shownAge(age: Duration): Duration = (age.inWholeSeconds + 1).seconds

private const val FULL_CIRCLE_DEGREES = 360.0
