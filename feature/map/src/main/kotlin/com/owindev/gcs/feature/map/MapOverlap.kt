package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Whether the vehicle's label, drawn [labelStart] to the right of the vehicle's centre and [label] in size, would cover
 * home, a circle of [homeRadius] at [home] from the vehicle's centre; then the label goes on the vehicle's left.
 */
internal fun labelCoversHome(home: DpOffset?, labelStart: Dp, label: DpSize, homeRadius: Dp): Boolean {
    if (home == null) return false
    val alongside = home.x > labelStart - homeRadius && home.x < labelStart + label.width + homeRadius
    val level = home.y > -(label.height / 2 + homeRadius) && home.y < label.height / 2 + homeRadius
    return alongside && level
}

/**
 * Whether the map's buttons and scale, [controls] wide at the bottom right, and the attribution, [attribution] wide at
 * the bottom left, would overlap on a map [width] wide with [gap] between them; then the buttons go above it.
 */
internal fun controlsAboveAttribution(width: Dp, controls: Dp, attribution: Dp, gap: Dp): Boolean =
    attribution > 0.dp && attribution + gap + controls > width
