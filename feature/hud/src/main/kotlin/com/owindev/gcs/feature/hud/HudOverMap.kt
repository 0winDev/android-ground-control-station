package com.owindev.gcs.feature.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.layout.CompactWidth
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import kotlin.math.ceil

/**
 * The [map] under the HUD's content: a [header] across the top, the [side] column (alerts, cards) and the [start]
 * column (telemetry). Each is on the theme's opaque surfaces, so its contrast holds over any tiles, and the map is told
 * the part they cover, so it centres the vehicle in the rest and keeps its controls out of them.
 *
 * On a wide screen [start] is on the left and [side] beside it, both over the map, and at most [MAX_COVERED] of each
 * side counts as covered, so some map is always left to centre it in. Below [CompactWidth] the [header] and [side]
 * stay over the top of the map and [start] goes under it, so no column is squeezed beside another. Wherever the
 * columns do not fit, they scroll instead of being cut.
 */
@Composable
internal fun HudOverMap(
    map: @Composable (insets: PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    start: @Composable ColumnScope.() -> Unit = {},
    side: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth < CompactWidth) {
            StackedHud(map = map, header = header, start = start, side = side)
        } else {
            WideHud(map = map, header = header, start = start, side = side)
        }
    }
}

@Composable
private fun WideHud(
    map: @Composable (insets: PaddingValues) -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    start: @Composable ColumnScope.() -> Unit,
    side: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    var startWidth by remember { mutableStateOf(0.dp) }
    var sideHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Each part sits one margin in from the edge or from the part before it.
        val insets = PaddingValues(
            start = minOf(stableInset(startWidth.coveredWithMargin()), maxWidth * MAX_COVERED),
            top = minOf(
                stableInset(headerHeight.coveredWithMargin() + sideHeight.coveredWithMargin()),
                maxHeight * MAX_COVERED,
            ),
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
                verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                content = header,
            )
            // Bounded by the height left, so on a phone on its side the columns scroll instead of being cut.
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(OverlayMargin)) {
                Column(
                    modifier = Modifier
                        .widthIn(max = StartWidth)
                        .onSizeChanged { startWidth = with(density) { it.width.toDp() } }
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                    content = start,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { sideHeight = with(density) { it.height.toDp() } }
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = side,
                )
            }
        }
    }
}

@Composable
private fun StackedHud(
    map: @Composable (insets: PaddingValues) -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    start: @Composable ColumnScope.() -> Unit,
    side: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var topHeight by remember { mutableStateOf(0.dp) }
    val covered = topHeight.coveredWithMargin()
    MapAbovePanel(
        // The minimum is the map left visible under the banners, never counting them.
        minMapHeight = covered + MinMapHeight,
        map = {
            Box(modifier = Modifier.fillMaxSize()) {
                map(PaddingValues(top = stableInset(covered)))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = OverlayMargin)
                        .onSizeChanged { topHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header()
                    side()
                }
            }
        },
        panel = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(all = OverlayMargin),
                verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                content = start,
            )
        },
    )
}

/**
 * The [map] on top and the [panel] under it, both the whole width: the panel takes the height it needs, but never so
 * much that the map gets less than [minMapHeight], and scrolls past that; the map takes the rest.
 */
@Composable
private fun MapAbovePanel(minMapHeight: Dp, map: @Composable () -> Unit, panel: @Composable () -> Unit) {
    Layout(contents = listOf(map, panel), modifier = Modifier.fillMaxSize()) { (mapPart, panelPart), constraints ->
        val height = constraints.maxHeight
        val panelMaxHeight = (height - minMapHeight.roundToPx()).coerceAtLeast(0)
        val panelPlaced = panelPart.single().measure(constraints.copy(minHeight = 0, maxHeight = panelMaxHeight))
        val mapHeight = height - panelPlaced.height
        val mapPlaced = mapPart.single().measure(Constraints.fixed(width = constraints.maxWidth, height = mapHeight))
        layout(width = constraints.maxWidth, height = height) {
            mapPlaced.place(x = 0, y = 0)
            panelPlaced.place(x = 0, y = mapHeight)
        }
    }
}

/**
 * [covered] rounded up to a whole [INSET_STEP], so a banner that grows or shrinks by a line of text does not move the
 * map's camera with it each time: only a change of layout past a step does.
 */
internal fun stableInset(covered: Dp): Dp = INSET_STEP * ceil(covered / INSET_STEP)

/** A part this size and the margin before it; nothing when the part is empty. */
private fun Dp.coveredWithMargin(): Dp = if (this > 0.dp) this + OverlayMargin else 0.dp

internal val OverlayMargin = 16.dp

/** The step the covered sides are rounded up to. */
internal val INSET_STEP = 16.dp

/** The share of the map's width or height that can count as covered. */
private const val MAX_COVERED = 0.6f

/** The telemetry column's width beside the alerts on a wide screen. */
private val StartWidth = 320.dp

/**
 * The map left visible under the banners on a phone: the 60 dp vehicle marker with room around it above the map's
 * bottom controls (a margin, 56 dp buttons and the scale bar, about 110 dp), which the map centres it clear of.
 */
private val MinMapHeight = 280.dp
