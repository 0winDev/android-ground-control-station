package com.owindev.gcs.feature.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import kotlin.math.roundToInt
import org.maplibre.compose.overlay.MapOverlayScope

/**
 * The vehicle where it was last reported, pointing along its heading on a map turned by [mapBearing], with its label.
 * Live, it is the primary colour; otherwise an outline in the stale colour inside a dashed ring, so a position that
 * cannot be trusted never looks like one that can (REQ-017, REQ-026, REQ-029).
 */
@Composable
internal fun MapOverlayScope.VehicleMarker(vehicle: MapVehicle, mapBearing: Double) {
    val position = vehicle.position.toPosition()
    val live = vehicle.shown == PositionShown.Live
    val color = if (live) MaterialTheme.colorScheme.primary else GcsTheme.colors.stale
    val label = vehicleLabel(vehicle)
    val outline = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = Modifier
            .placedAt(position)
            .size(MARKER_SIZE)
            .semantics { contentDescription = label },
    ) {
        if (live) {
            drawCircle(color = color.copy(alpha = HALO_ALPHA))
        } else {
            drawCircle(
                color = color,
                radius = size.minDimension / 2 - RING_WIDTH.toPx(),
                style = Stroke(
                    width = RING_WIDTH.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(RING_DASH.toPx(), RING_DASH.toPx())),
                ),
            )
        }
        val heading = vehicle.heading
        if (heading == null) {
            drawCircle(color = color, radius = DOT_RADIUS.toPx())
        } else {
            drawArrow(rotation = (heading - mapBearing).toFloat(), color = color, outline = outline, filled = live)
        }
    }
    MarkerLabel(
        text = label,
        color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .placedAt(position, alignment = Alignment.CenterStart)
            .padding(start = MARKER_SIZE / 2 + LABEL_GAP),
    )
}

/** Home, as the prototype draws it: an "H" in a ringed circle. */
@Composable
internal fun HomeMarker(modifier: Modifier = Modifier) {
    val description = stringResource(id = R.string.map_home)
    Surface(
        modifier = modifier
            .size(HOME_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = HOME_BORDER, color = MaterialTheme.colorScheme.onSurface),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(id = R.string.map_home_letter),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MarkerLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = GcsTheme.telemetryTypography.coordinate.copy(
                fontSize = LABEL_SIZE,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
        )
    }
}

@Composable
private fun vehicleLabel(vehicle: MapVehicle): String = when (val shown = vehicle.shown) {
    PositionShown.Live ->
        stringResource(id = R.string.map_vehicle_live, vehicle.systemId, vehicle.altitudeAboveHome.roundToInt())

    PositionShown.NotVerified -> stringResource(id = R.string.map_vehicle_not_verified, vehicle.systemId)

    is PositionShown.Stale ->
        stringResource(id = R.string.map_vehicle_stale, vehicle.systemId, shown.age.inWholeSeconds)

    is PositionShown.LinkLost ->
        stringResource(id = R.string.map_vehicle_last_position, vehicle.systemId, shown.age.inWholeSeconds)
}

/**
 * The prototype's arrow, `M0 -20 L13 15 L0 8 L-13 15 Z` in a 56-unit marker, scaled to this one and turned
 * [rotation] degrees clockwise from screen-up.
 */
private fun DrawScope.drawArrow(rotation: Float, color: Color, outline: Color, filled: Boolean) {
    val unit = size.minDimension / PROTOTYPE_MARKER_UNITS
    val centre = Offset(size.width / 2, size.height / 2)
    val arrow = Path().apply {
        moveTo(centre.x, centre.y - ARROW_TIP * unit)
        lineTo(centre.x + ARROW_HALF_WIDTH * unit, centre.y + ARROW_BASE * unit)
        lineTo(centre.x, centre.y + ARROW_NOTCH * unit)
        lineTo(centre.x - ARROW_HALF_WIDTH * unit, centre.y + ARROW_BASE * unit)
        close()
    }
    withTransform({ rotate(degrees = rotation, pivot = centre) }) {
        if (filled) {
            drawPath(path = arrow, color = color, style = Fill)
            drawPath(path = arrow, color = outline, style = Stroke(width = ARROW_STROKE.toPx()))
        } else {
            drawPath(path = arrow, color = color, style = Stroke(width = ARROW_STROKE.toPx()))
        }
    }
}

private val MARKER_SIZE = 60.dp
private val HOME_SIZE = 34.dp
private val HOME_BORDER = 2.5.dp
private val RING_WIDTH = 1.5.dp
private val ARROW_STROKE = 2.dp
private val DOT_RADIUS = 6.dp
private val LABEL_GAP = 4.dp
private val LABEL_SIZE = 12.sp

/** The prototype's halo, the primary colour at 12 %. */
private const val HALO_ALPHA = 0.12f

/** The prototype's dashed ring, 4 on and 4 off. */
private val RING_DASH = 4.dp

/** The prototype's marker is 56 units across (`r="28"`) and its arrow spans y -20 to 15, x ±13. */
private const val PROTOTYPE_MARKER_UNITS = 56f
private const val ARROW_TIP = 20f
private const val ARROW_BASE = 15f
private const val ARROW_NOTCH = 8f
private const val ARROW_HALF_WIDTH = 13f
