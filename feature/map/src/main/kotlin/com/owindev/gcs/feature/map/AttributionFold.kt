package com.owindev.gcs.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapState

/**
 * When the attribution folds into a button: only once it has been shown, then after [ATTRIBUTION_SHOWN_FOR] or at the
 * first gesture on the map, as the OSMF attribution guidelines allow (1.5.2). A gesture before it has shown, or before
 * the tiles have an attribution, never folds it, and a new attribution shows again before it can fold.
 */
internal class AttributionFold {
    var folded by mutableStateOf(false)
        private set
    private var shown = false

    fun onShown() {
        shown = true
    }

    fun onShownLongEnough() = foldIfShown()

    fun onGesture() = foldIfShown()

    fun onOpen() {
        folded = false
    }

    fun onAttributionChanged() {
        shown = false
        folded = false
    }

    private fun foldIfShown() {
        if (shown) folded = true
    }
}

/** Whether the attribution is folded, on a map where it is [foldable] and the tiles have [attributions]. */
@Composable
internal fun rememberAttributionFold(
    mapState: MapState,
    attributions: List<String>,
    foldable: Boolean,
): AttributionFold {
    val fold = remember { AttributionFold() }
    LaunchedEffect(attributions) { fold.onAttributionChanged() }
    LaunchedEffect(foldable, fold.folded, attributions) {
        if (foldable && !fold.folded && attributions.isNotEmpty()) {
            fold.onShown()
            delay(ATTRIBUTION_SHOWN_FOR)
            fold.onShownLongEnough()
        }
    }
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraMoveReason }
            .filter { it == CameraMoveReason.Gesture }
            .collect { fold.onGesture() }
    }
    return fold
}

/** The tiles' attribution as plain text, or while [folded] a button that opens it again. */
@Composable
internal fun MapAttribution(
    attributions: List<String>,
    folded: Boolean,
    onOpen: () -> Unit,
    onSizeChange: (DpSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (folded) {
        Box(modifier = modifier) {
            ControlButton(description = stringResource(id = R.string.map_attribution_show), onClick = onOpen) {
                val color = MaterialTheme.colorScheme.onSurface
                Canvas(modifier = Modifier.size(ICON_SIZE)) { drawInfo(color) }
            }
        }
    } else if (attributions.isNotEmpty()) {
        OnSurface(modifier = modifier.onSizeChanged { onSizeChange(with(density) { it.toSize().toDpSize() }) }) {
            Text(
                text = plainAttribution(attributions),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** An "i" in a ring. */
private fun DrawScope.drawInfo(color: Color) {
    val stroke = ICON_STROKE.toPx()
    val unit = size.minDimension / ICON_UNITS
    drawCircle(color = color, radius = INFO_RING * unit, style = Stroke(width = stroke))
    drawCircle(color = color, radius = stroke, center = center + Offset(0f, -INFO_DOT * unit))
    drawLine(
        color = color,
        start = center + Offset(0f, -INFO_STEM_TOP * unit),
        end = center + Offset(0f, INFO_STEM_BOTTOM * unit),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private const val INFO_RING = 9f
private const val INFO_DOT = 4.5f
private const val INFO_STEM_TOP = 1f
private const val INFO_STEM_BOTTOM = 5f

/** How long the attribution shows before it folds: the OSMF attribution guidelines' five seconds (1.5.2). */
private val ATTRIBUTION_SHOWN_FOR = 5.seconds
