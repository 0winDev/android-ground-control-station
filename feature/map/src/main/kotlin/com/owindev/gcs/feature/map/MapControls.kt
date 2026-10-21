package com.owindev.gcs.feature.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.LocalViewportInsets
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.ScaleBar
import org.maplibre.compose.overlay.attributions

/**
 * The map's own controls, inside the part of the map nothing covers and on the theme's opaque surfaces, so their
 * contrast holds over any tiles: north up, zoom, recenter, the scale and the tiles' attribution, always visible and
 * plain text, never a link a stray tap would follow out of the app.
 * They cover the map too, so a vehicle under them counts as off the visible map.
 */
@Composable
internal fun MapOverlayScope.MapControls(mapState: MapState, vehicle: MapVehicle?, onRecenter: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bearing = mapState.cameraPosition.bearing
    var controlsHeight by remember { mutableStateOf(0.dp) }
    var attributionHeight by remember { mutableStateOf(0.dp) }
    val bottomCovered = CONTROLS_MARGIN + maxOf(controlsHeight, attributionHeight)
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(LocalViewportInsets.current)
            .padding(CONTROLS_MARGIN),
    ) {
        if (bearing != 0.0) {
            CompassButton(
                bearing = bearing,
                onClick = { scope.launch { mapState.animateCamera(CameraUpdate(bearing = 0.0)) } },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(CONTROLS_SPACING),
            horizontalAlignment = Alignment.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CONTROLS_SPACING)) {
                RecenterButton(
                    towardsVehicle = offScreenDirectionOf(mapState, vehicle, bottomCovered),
                    enabled = vehicle != null,
                    onClick = onRecenter,
                )
                ZoomButton(zoomIn = true) {
                    scope.launch { mapState.animateCamera(CameraUpdate(zoom = mapState.cameraPosition.zoom + 1)) }
                }
                ZoomButton(zoomIn = false) {
                    scope.launch { mapState.animateCamera(CameraUpdate(zoom = mapState.cameraPosition.zoom - 1)) }
                }
            }
            OnSurface {
                ScaleBar(
                    metersPerDp = { mapState.viewport?.metersPerDpAtCenter ?: 0.0 },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    haloColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
        val attributions = mapState.style.attributions()
        if (attributions.isNotEmpty()) {
            OnSurface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .onSizeChanged { attributionHeight = with(density) { it.height.toDp() } },
            ) {
                Text(
                    text = plainAttribution(attributions),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Where the vehicle is from the centre of the visible map while it is outside it, whether or not the camera is meant
 * to follow it, so the real camera is never trusted less than the flag; `null` otherwise. The map's own controls cover
 * the [bottomCovered] strip.
 */
@Composable
private fun offScreenDirectionOf(mapState: MapState, vehicle: MapVehicle?, bottomCovered: Dp): Float? {
    val insets = LocalViewportInsets.current
    val layoutDirection = LocalLayoutDirection.current
    val viewport = mapState.viewport
    val location = vehicle?.let { mapState.screenLocationFromPosition(it.position.toPosition()) }
    if (viewport == null || location == null) return null
    val visible = DpRect(
        left = insets.calculateLeftPadding(layoutDirection),
        top = insets.calculateTopPadding(),
        right = viewport.size.width - insets.calculateRightPadding(layoutDirection),
        bottom = viewport.size.height - insets.calculateBottomPadding() - bottomCovered,
    )
    return offScreenDirection(location, visible)
}

/**
 * Back to the vehicle. While the vehicle is off the visible map, it shows an arrow towards it instead of the
 * crosshair, so where the vehicle is is never lost.
 */
@Composable
private fun RecenterButton(towardsVehicle: Float?, enabled: Boolean, onClick: () -> Unit) {
    val description = if (towardsVehicle == null) {
        stringResource(id = R.string.map_recenter)
    } else {
        stringResource(id = R.string.map_recenter_off_screen)
    }
    val accent = if (towardsVehicle == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
    ControlButton(description = description, enabled = enabled, onClick = onClick) {
        Canvas(modifier = Modifier.size(ICON_SIZE)) {
            if (towardsVehicle == null) drawCrosshair(accent) else drawPointer(towardsVehicle, accent)
        }
    }
}

@Composable
private fun ZoomButton(zoomIn: Boolean, onClick: () -> Unit) {
    val description = stringResource(id = if (zoomIn) R.string.map_zoom_in else R.string.map_zoom_out)
    val color = MaterialTheme.colorScheme.onSurface
    ControlButton(description = description, onClick = onClick) {
        Canvas(modifier = Modifier.size(ICON_SIZE)) {
            val stroke = ICON_STROKE.toPx()
            val inset = size.minDimension * ICON_INSET
            drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), stroke, StrokeCap.Round)
            if (zoomIn) {
                drawLine(color, Offset(center.x, inset), Offset(center.x, size.height - inset), stroke, StrokeCap.Round)
            }
        }
    }
}

/** North up again; it shows only while the map is turned, its needle pointing north. */
@Composable
private fun CompassButton(bearing: Double, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier) {
        ControlButton(description = stringResource(id = R.string.map_north_up), onClick = onClick) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(ICON_SIZE)) { drawPointer(-bearing.toFloat(), color) }
                Text(
                    text = stringResource(id = R.string.map_north_letter),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(GcsTheme.sizes.button)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun OnSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** The prototype's recenter icon: a ring with four ticks. */
private fun DrawScope.drawCrosshair(color: Color) {
    val stroke = ICON_STROKE.toPx()
    val unit = size.minDimension / ICON_UNITS
    drawCircle(color = color, radius = CROSSHAIR_RING * unit, style = Stroke(width = stroke))
    val ticks = listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f))
    ticks.forEach { direction ->
        drawLine(
            color = color,
            start = center + direction * (CROSSHAIR_TICK_START * unit),
            end = center + direction * (CROSSHAIR_TICK_END * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** A filled arrowhead turned [degrees] clockwise from screen-up. */
private fun DrawScope.drawPointer(degrees: Float, color: Color) {
    val unit = size.minDimension / ICON_UNITS
    val pointer = Path().apply {
        moveTo(center.x, center.y - POINTER_TIP * unit)
        lineTo(center.x + POINTER_HALF_WIDTH * unit, center.y + POINTER_BASE * unit)
        lineTo(center.x, center.y + POINTER_NOTCH * unit)
        lineTo(center.x - POINTER_HALF_WIDTH * unit, center.y + POINTER_BASE * unit)
        close()
    }
    withTransform({ rotate(degrees = degrees, pivot = center) }) { drawPath(pointer, color, style = Fill) }
}

private val CONTROLS_MARGIN = 16.dp
private val CONTROLS_SPACING = 8.dp
private val ICON_SIZE: Dp = 24.dp
private val ICON_STROKE: Dp = 2.dp
private const val ICON_INSET = 0.2f

/** The prototype's icons are drawn on a 24-unit grid. */
private const val ICON_UNITS = 24f
private const val CROSSHAIR_RING = 4f
private const val CROSSHAIR_TICK_START = 6f
private const val CROSSHAIR_TICK_END = 10f
private const val POINTER_TIP = 9f
private const val POINTER_BASE = 7f
private const val POINTER_NOTCH = 3f
private const val POINTER_HALF_WIDTH = 6f
