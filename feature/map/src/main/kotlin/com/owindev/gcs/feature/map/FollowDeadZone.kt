package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * How far on screen the vehicle may sit from the centre of the visible map before the camera follows it. At the
 * follow zoom (17) it is some 2.4 m at Canberra's latitude, more than a GPS's noise at rest, so a vehicle standing
 * still never shakes the map; a moving one passes it within a sample or two, so the camera lags it by this much at most.
 */
internal val FOLLOW_DEAD_ZONE: Dp = 4.dp

internal fun withinDeadZone(centre: DpOffset, vehicle: DpOffset): Boolean =
    hypot((vehicle.x - centre.x).value, (vehicle.y - centre.y).value) < FOLLOW_DEAD_ZONE.value
