package com.owindev.gcs.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

internal val NightColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Night1,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanLight,
    secondary = TextSecondary,
    onSecondary = Night1,
    background = Night0,
    onBackground = TextPrimary,
    surface = Night1,
    onSurface = TextPrimary,
    surfaceVariant = Night4,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Night0,
    surfaceContainerLow = Night2,
    surfaceContainer = Night3,
    surfaceContainerHigh = Night4,
    surfaceContainerHighest = Night5,
    outline = LineStrong,
    outlineVariant = Line,
    error = Red,
    onError = Night1,
    errorContainer = RedContainer,
    onErrorContainer = RedLight,
)

internal val DayColorScheme = lightColorScheme(
    primary = DayCyan,
    onPrimary = Day1,
    primaryContainer = DayCyanContainer,
    onPrimaryContainer = DayCyanDark,
    secondary = DayTextSecondary,
    onSecondary = Day1,
    background = Day0,
    onBackground = DayTextPrimary,
    surface = Day1,
    onSurface = DayTextPrimary,
    surfaceVariant = Day4,
    onSurfaceVariant = DayTextMuted,
    surfaceContainerLowest = Day1,
    surfaceContainerLow = Day2,
    surfaceContainer = Day3,
    surfaceContainerHigh = Day4,
    surfaceContainerHighest = Day5,
    outline = DayLineStrong,
    outlineVariant = DayLine,
    error = DayRed,
    onError = Day1,
    errorContainer = DayRedContainer,
    onErrorContainer = DayRedDark,
)

/** The two palettes the operator can choose between (REQ-030): night by default, high-contrast day outdoors. */
enum class GcsPalette {
    NIGHT,
    DAY,
}

private val LocalGcsColors = staticCompositionLocalOf { NightGcsColors }
private val LocalTelemetryTypography = staticCompositionLocalOf { GcsTelemetryTypography }
private val LocalGcsSizes = staticCompositionLocalOf { DefaultGcsSizes }

/**
 * GCS theme: the night or the high-contrast day [palette] (REQ-030), Barlow / Barlow Condensed / IBM Plex Mono and
 * large controls. Both palettes give every semantic token the same meaning.
 */
@Composable
fun GcsTheme(palette: GcsPalette = GcsPalette.NIGHT, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGcsColors provides if (palette == GcsPalette.DAY) DayGcsColors else NightGcsColors,
        LocalTelemetryTypography provides GcsTelemetryTypography,
        LocalGcsSizes provides DefaultGcsSizes,
    ) {
        MaterialTheme(
            colorScheme = if (palette == GcsPalette.DAY) DayColorScheme else NightColorScheme,
            typography = GcsTypography,
            content = content,
        )
    }
}

/** GCS-specific tokens on top of [MaterialTheme]. */
object GcsTheme {
    val colors: GcsColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGcsColors.current

    val telemetryTypography: TelemetryTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTelemetryTypography.current

    val sizes: GcsSizes
        @Composable
        @ReadOnlyComposable
        get() = LocalGcsSizes.current
}
