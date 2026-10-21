package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Where to point for a vehicle at [location] on screen, in degrees clockwise from screen-up, from the centre of the
 * [visible] part of the map; `null` while the vehicle is inside it. Screen-space, so it already accounts for the
 * map's rotation and projection.
 */
internal fun offScreenDirection(location: DpOffset, visible: DpRect): Float? {
    val inside = location.x in visible.left..visible.right && location.y in visible.top..visible.bottom
    if (inside) return null
    val dx = (location.x - (visible.left + visible.right) / 2).value
    val dy = (location.y - (visible.top + visible.bottom) / 2).value
    // Screen y grows downwards, so up is -y; atan2(dx, -dy) is 0 up and grows clockwise.
    val degrees = (atan2(dx, -dy) * HALF_TURN_DEGREES / PI).toFloat()
    return if (degrees < 0) degrees + FULL_TURN_DEGREES else degrees
}

private const val HALF_TURN_DEGREES = 180.0
private const val FULL_TURN_DEGREES = 360f
