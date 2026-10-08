package com.owindev.gcs.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Control sizes for field use (outdoors, possibly with gloves), taken from the UI prototype.
 * Nothing interactive may be smaller than [minTouchTarget].
 */
@Immutable
data class GcsSizes(
    val minTouchTarget: Dp,
    val button: Dp,
    val tab: Dp,
    val gloveButton: Dp,
)

internal val DefaultGcsSizes = GcsSizes(
    minTouchTarget = 48.dp,
    button = 56.dp,
    tab = 64.dp,
    gloveButton = 72.dp,
)
