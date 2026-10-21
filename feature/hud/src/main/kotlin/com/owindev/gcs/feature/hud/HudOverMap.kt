package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * column beside it. Each is on the theme's opaque surfaces, so its contrast holds over any tiles. The map is told the
 * part they cover, so it centres the vehicle in the rest and keeps its controls out of them; on a screen too small
 * for them, at most [MAX_COVERED] of each side counts as covered, so some map is always left to centre it in.
 */
@Composable
internal fun HudOverMap(
    map: @Composable (insets: PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    start: @Composable ColumnScope.() -> Unit = {},
    side: @Composable ColumnScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    var startWidth by remember { mutableStateOf(0.dp) }
    var sideHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Each part sits one margin in from the edge or from the part before it.
        val insets = PaddingValues(
            start = minOf(startWidth.coveredWithMargin(), maxWidth * MAX_COVERED),
            top = minOf(headerHeight.coveredWithMargin() + sideHeight.coveredWithMargin(), maxHeight * MAX_COVERED),
        )
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
    }
}

/** A part this size and the margin before it; nothing when the part is empty. */
private fun Dp.coveredWithMargin(): Dp = if (this > 0.dp) this + OverlayMargin else 0.dp

internal val OverlayMargin = 16.dp

/** The share of the map's width or height that can count as covered. */
private const val MAX_COVERED = 0.6f
