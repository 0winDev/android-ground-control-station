package com.owindev.gcs.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

private val LocalGcsColors = staticCompositionLocalOf { NightGcsColors }
private val LocalTelemetryTypography = staticCompositionLocalOf { GcsTelemetryTypography }
private val LocalGcsSizes = staticCompositionLocalOf { DefaultGcsSizes }

/**
 * GCS theme: night palette, Barlow / Barlow Condensed / IBM Plex Mono and large controls.
 * A high-contrast day theme arrives with the alerts and night mode work (v0.2).
 */
@Composable
fun GcsTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGcsColors provides NightGcsColors,
        LocalTelemetryTypography provides GcsTelemetryTypography,
        LocalGcsSizes provides DefaultGcsSizes,
    ) {
        MaterialTheme(
            colorScheme = NightColorScheme,
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
