package com.owindev.gcs.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing between and inside the parts of a screen, from the UI prototype: wide on a tablet, tighter on a phone
 * ([GcsCompact]). Never a touch target: those stay [GcsSizes] on every screen.
 */
@Immutable
data class GcsSpacing(val screen: Dp, val content: Dp, val chip: Dp, val chipPadding: Dp, val largeIcon: Dp)

internal val DefaultGcsSpacing = GcsSpacing(
    screen = 24.dp,
    content = 12.dp,
    chip = 8.dp,
    chipPadding = 12.dp,
    largeIcon = 72.dp,
)

/** The phone boards' padding (12 to 16 px) and their 40 px emergency icon. */
internal val CompactGcsSpacing = GcsSpacing(
    screen = 12.dp,
    content = 8.dp,
    chip = 6.dp,
    chipPadding = 8.dp,
    largeIcon = 40.dp,
)
