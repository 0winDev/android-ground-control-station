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
internal val LineStrong = Color(0xFF3A444E)

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
