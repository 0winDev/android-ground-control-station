package com.owindev.gcs.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.owindev.gcs.core.designsystem.R

// Fonts are bundled (OFL, see assets/licenses): the app must work offline in the field.
internal val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

internal val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

internal val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val LabelLetterSpacing = 0.08.em

internal val GcsTypography = Typography(
    displayLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 48.sp),
    headlineLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = LabelLetterSpacing,
    ),
    labelMedium = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = LabelLetterSpacing,
    ),
)

/** Monospaced styles for numeric telemetry and coordinates, so digits don't jitter while updating. */
@Immutable
data class TelemetryTypography(val valueLarge: TextStyle, val value: TextStyle, val coordinate: TextStyle)

internal val GcsTelemetryTypography = TelemetryTypography(
    valueLarge = TextStyle(fontFamily = IbmPlexMono, fontWeight = FontWeight.Medium, fontSize = 28.sp),
    value = TextStyle(fontFamily = IbmPlexMono, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    coordinate = TextStyle(fontFamily = IbmPlexMono, fontWeight = FontWeight.Normal, fontSize = 14.sp),
)
