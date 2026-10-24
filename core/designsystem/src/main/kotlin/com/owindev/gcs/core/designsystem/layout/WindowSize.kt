package com.owindev.gcs.core.designsystem.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Material's window size class breakpoints:
// https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes

/** Below this width a screen is a phone's (compact width class): what sits side by side on a tablet stacks. */
val CompactWidth: Dp = 600.dp

/** Below this height a screen is short (compact height class), as a phone on its side: chrome takes less height. */
val CompactHeight: Dp = 480.dp
