package com.owindev.gcs.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Night palette, taken from the UI prototype.
internal val Night0 = Color(0xFF0B0E11)
internal val Night1 = Color(0xFF0E1216)
internal val Night2 = Color(0xFF11161A)
internal val Night3 = Color(0xFF161B20)
internal val Night4 = Color(0xFF1D242B)
internal val Night5 = Color(0xFF232B33)
internal val Line = Color(0xFF2A323A)

// At least 3:1 on the surfaces (WCAG non-text contrast), so a control outlined with it can be told apart.
internal val LineStrong = Color(0xFF626E78)

internal val TextPrimary = Color(0xFFE6EAED)
internal val TextSecondary = Color(0xFFB4BDC4)
internal val TextMuted = Color(0xFF9AA5AE)
internal val TextDisabled = Color(0xFF8A949C)

internal val Cyan = Color(0xFF4FB3D9)
internal val CyanLight = Color(0xFF8ED0EA)
internal val CyanContainer = Color(0xFF1C2C35)
internal val Green = Color(0xFF4CC38A)
internal val GreenContainer = Color(0xFF173226)
internal val Amber = Color(0xFFF2A33A)
internal val OnAmber = Color(0xFF1A1200)
internal val AmberContainer = Color(0xFF2A2214)
internal val Red = Color(0xFFE5484D)
internal val RedLight = Color(0xFFFF8A8D)
internal val RedContainer = Color(0xFF2A1416)

// Day palette: high contrast for daylight. Main text is at least 7:1 (WCAG AAA) on the backgrounds and every other
// text at least 4.5:1 (AA), checked in ContrastTest; the accents are darkened so they read on light surfaces.
internal val Day0 = Color(0xFFF2F4F6)
internal val Day1 = Color(0xFFFFFFFF)
internal val Day2 = Color(0xFFF7F8FA)
internal val Day3 = Color(0xFFEBEEF1)
internal val Day4 = Color(0xFFE1E5E9)
internal val Day5 = Color(0xFFD6DBE0)
internal val DayLine = Color(0xFFC3CAD1)
internal val DayLineStrong = Color(0xFF7D8790)

internal val DayTextPrimary = Color(0xFF0B0E11)
internal val DayTextSecondary = Color(0xFF2E3740)
internal val DayTextMuted = Color(0xFF434D57)
internal val DayTextDisabled = Color(0xFF5E6872)

internal val DayCyan = Color(0xFF005E80)
internal val DayCyanDark = Color(0xFF004A66)
internal val DayCyanContainer = Color(0xFFD6ECF5)
internal val DayGreen = Color(0xFF16673F)
internal val DayGreenContainer = Color(0xFFD9F0E3)
internal val DayAmber = Color(0xFF8A4B00)
internal val OnDayAmber = Color(0xFFFFFFFF)
internal val DayAmberContainer = Color(0xFFFBE9CF)
internal val DayRed = Color(0xFFB3261E)
internal val DayRedDark = Color(0xFF8C1D18)
internal val DayRedContainer = Color(0xFFFBE4E2)

/**
 * Semantic colors that Material's color scheme has no slot for. Alert levels follow the aviation
 * convention: advisory < caution < emergency. Never convey a level with color alone.
 */
@Immutable
data class GcsColors(
    val advisory: Color,
    val advisoryContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val emergency: Color,
    val onEmergencyContainer: Color,
    val emergencyContainer: Color,
    val nominal: Color,
    val nominalContainer: Color,
    /** Telemetry value older than its staleness threshold. */
    val stale: Color,
)

internal val NightGcsColors = GcsColors(
    advisory = Cyan,
    advisoryContainer = CyanContainer,
    caution = Amber,
    onCaution = OnAmber,
    cautionContainer = AmberContainer,
    emergency = Red,
    onEmergencyContainer = RedLight,
    emergencyContainer = RedContainer,
    nominal = Green,
    nominalContainer = GreenContainer,
    stale = TextDisabled,
)

/** The same meanings as [NightGcsColors], in colors that keep their contrast in daylight. */
internal val DayGcsColors = GcsColors(
    advisory = DayCyan,
    advisoryContainer = DayCyanContainer,
    caution = DayAmber,
    onCaution = OnDayAmber,
    cautionContainer = DayAmberContainer,
    emergency = DayRed,
    onEmergencyContainer = DayRedDark,
    emergencyContainer = DayRedContainer,
    nominal = DayGreen,
    nominalContainer = DayGreenContainer,
    stale = DayTextDisabled,
)
