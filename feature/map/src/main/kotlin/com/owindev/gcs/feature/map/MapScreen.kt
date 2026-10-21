package com.owindev.gcs.feature.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.Position

/**
 * The map of the active vehicle (REQ-034) in the [palette] in use. [viewportInsets] is the part of the map other
 * content covers: the camera centres the vehicle in what is left, and the map's controls stay out of it.
 */
// Without @UiComposable the compiler infers MapLibre's own applier from the map content, and callers cannot use it.
@Composable
@UiComposable
fun MapScreen(
    palette: GcsPalette,
    modifier: Modifier = Modifier,
    viewportInsets: PaddingValues = PaddingValues(0.dp),
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MapContent(
        state = state,
        palette = palette,
        viewportInsets = viewportInsets,
        onMapPan = viewModel::onMapPanned,
        onRecenter = viewModel::onRecenter,
        modifier = modifier,
    )
}

@Composable
@UiComposable
internal fun MapContent(
    state: MapUiState,
    palette: GcsPalette,
    viewportInsets: PaddingValues,
    onMapPan: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteStyle = remoteStyleOf(palette)
    var failedStyle by remember { mutableStateOf<Any?>(null) }
    val trailColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRAIL_ALPHA)
    val mapState = rememberMapState(
        baseStyle = if (failedStyle ==
            remoteStyle
        ) {
            fallbackStyleOf(MaterialTheme.colorScheme.background)
        } else {
            remoteStyle
        },
        initialCameraPosition = CameraPosition(zoom = WORLD_ZOOM),
    ) {
        TrailLayers(trail = state.trail, color = trailColor)
    }
    val currentRemoteStyle by rememberUpdatedState(remoteStyle)
    val currentOnMapPan by rememberUpdatedState(onMapPan)
    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) failedStyle = currentRemoteStyle }
    }
    // The network may come back: the remote style is tried again while the fallback is shown.
    LaunchedEffect(failedStyle) {
        if (failedStyle != null) {
            delay(STYLE_RETRY)
            failedStyle = null
        }
    }
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraMoveReason }
            .filter { it == CameraMoveReason.Gesture }
            .collect { currentOnMapPan() }
    }
    FollowVehicle(mapState = mapState, vehicle = state.vehicle, following = state.following)
    MaplibreMap(
        modifier = modifier.fillMaxSize(),
        state = mapState,
        viewportInsets = viewportInsets,
        overlay = {
            state.home?.let { HomeMarker(modifier = Modifier.placedAt(it.toPosition())) }
            state.vehicle?.let { VehicleMarker(vehicle = it, mapBearing = mapState.cameraPosition.bearing) }
            MapControls(mapState = mapState, vehicle = state.vehicle, following = state.following, onRecenter)
        },
    )
}

/** Keeps the vehicle at the centre of the visible map while the camera follows it, zooming in on the first fix. */
@Composable
private fun FollowVehicle(mapState: MapState, vehicle: MapVehicle?, following: Boolean) {
    val position = vehicle?.position
    LaunchedEffect(mapState, position, following) {
        if (position == null || !following) return@LaunchedEffect
        val zoom = FOLLOW_ZOOM.takeIf { mapState.cameraPosition.zoom < MIN_FOLLOW_ZOOM }
        mapState.animateCamera(
            update = CameraUpdate(center = position.toPosition(), zoom = zoom),
            animation = CameraAnimation.Ease { duration = FOLLOW_STEP },
        )
    }
}

/**
 * The trail (REQ-035): its segments as lines, never joined to each other, and a segment of one point as a dot, so a
 * stretch the GCS saw at 1 Hz or less is still on the map.
 */
@Composable
@MaplibreComposable
private fun TrailLayers(trail: List<List<MapPoint>>, color: Color) {
    val lines = trail.filter { it.size >= 2 }.map { segment -> segment.map(MapPoint::toPosition) }
    val dots = trail.filter { it.size == 1 }.map { it.single().toPosition() }
    LineLayer(
        id = "trail-lines",
        source = rememberGeoJsonSource(GeoJsonData.Features(MultiLineString(lines))),
        color = const(color),
        width = const(TRAIL_WIDTH),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    CircleLayer(
        id = "trail-dots",
        source = rememberGeoJsonSource(GeoJsonData.Features(MultiPoint(dots))),
        color = const(color),
        radius = const(TRAIL_WIDTH),
    )
}

internal fun MapPoint.toPosition() = Position(longitude = longitude, latitude = latitude)

/** The prototype's trail: the primary text colour at 35 %, 2 dp wide. */
private const val TRAIL_ALPHA = 0.35f
private val TRAIL_WIDTH = 2.dp

/** The whole world, until the first position. */
private const val WORLD_ZOOM = 1.0

/** About 150 m across a tablet's map, as the prototype's: close enough to see the trail of a hover. */
private const val FOLLOW_ZOOM = 17.0

/** Below this the operator could not tell where the vehicle is, so following zooms in. */
private const val MIN_FOLLOW_ZOOM = 12.0

/** GLOBAL_POSITION_INT's period at 5 Hz, so each step ends as the next position arrives. */
private val FOLLOW_STEP = 200.milliseconds

private val STYLE_RETRY = 30.seconds
