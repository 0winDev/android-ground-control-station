package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The [map] under the HUD's content: a [header] across the top, then the [start] column on the left and the [side]
 * column beside it, and [centre] in the middle of what is left. Each is on the theme's opaque surfaces, so its
 * contrast holds over any tiles. The map is told the part they cover, so it centres the vehicle in the rest and keeps
 * its controls out of them.
 */
@Composable
internal fun HudOverMap(
    map: @Composable (insets: PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    start: @Composable ColumnScope.() -> Unit = {},
    side: @Composable ColumnScope.() -> Unit = {},
    centre: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    var startWidth by remember { mutableStateOf(0.dp) }
    var sideHeight by remember { mutableStateOf(0.dp) }
    // Each part sits one margin in from the edge or from the part before it.
    val insets = PaddingValues(
        start = startWidth.coveredWithMargin(),
        top = headerHeight.coveredWithMargin() + sideHeight.coveredWithMargin(),
    )
    Box(modifier = modifier.fillMaxSize()) {
        map(insets)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = OverlayMargin),
            verticalArrangement = Arrangement.spacedBy(OverlayMargin),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(ContentSpacing),
                content = header,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(OverlayMargin)) {
                Column(
                    modifier = Modifier.onSizeChanged { startWidth = with(density) { it.width.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(ContentSpacing),
                    content = start,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { sideHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(ContentSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = side,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(all = OverlayMargin),
            contentAlignment = Alignment.Center,
        ) {
            centre()
        }
    }
}

/** A part this size and the margin before it; nothing when the part is empty. */
private fun Dp.coveredWithMargin(): Dp = if (this > 0.dp) this + OverlayMargin else 0.dp

internal val OverlayMargin = 16.dp
