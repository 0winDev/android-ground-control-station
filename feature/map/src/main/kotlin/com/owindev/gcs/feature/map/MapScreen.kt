package com.owindev.gcs.feature.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
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
    val context = LocalContext.current
    val nightStyle by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(NIGHT_STYLE_ASSET).bufferedReader().use { it.readText() }
        }
    }
    // The vehicle is drawn over the map only once the map has a viewport, which needs a style presented first: the
    // map starts on the fallback, which needs no network, so the vehicle shows at once, and moves to the remote style
    // as soon as it is up.
    var presented by remember { mutableStateOf(false) }
    var failedStyle by remember { mutableStateOf<BaseStyle?>(null) }
    val remoteStyle = nightStyle?.let { remoteStyleOf(palette, it) }
    val useRemote = presented && remoteStyle != null && failedStyle != remoteStyle
    val trailColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRAIL_ALPHA)
    val baseStyle = remoteStyle.takeIf { useRemote } ?: fallbackStyleOf(MaterialTheme.colorScheme.background)
    val mapState = rememberMapState(
        baseStyle = baseStyle,
        initialCameraPosition = CameraPosition(zoom = WORLD_ZOOM),
    ) {
        TrailLayers(trail = state.trail, color = trailColor)
    }
    val currentOnMapPan by rememberUpdatedState(onMapPan)
    // The camera centres the vehicle above the map's own controls too, so on a short map it is never drawn under them.
    var controlsCovered by remember { mutableStateOf(0.dp) }
    val layoutDirection = LocalLayoutDirection.current
    val cameraInsets = PaddingValues(
        start = viewportInsets.calculateStartPadding(layoutDirection),
        top = viewportInsets.calculateTopPadding(),
        end = viewportInsets.calculateEndPadding(layoutDirection),
        bottom = viewportInsets.calculateBottomPadding() + controlsCovered,
    )
    LaunchedEffect(mapState) {
        mapState.awaitViewport()
        presented = true
    }
    // A network that answers nothing (a telemetry Wi-Fi without internet) never fails the load, so it is timed out.
    // Trying again would drop the trail with the style each time, so the fallback stays until the palette changes.
    LaunchedEffect(mapState, remoteStyle, useRemote) {
        if (!useRemote) return@LaunchedEffect
        val loaded = withTimeoutOrNull(STYLE_TIMEOUT) {
            snapshotFlow {
                mapState.style.loadState
            }.first { it == StyleLoadState.Ready || it is StyleLoadState.Failed }
        }
        if (loaded != StyleLoadState.Ready) failedStyle = remoteStyle
    }
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraMoveReason }
            .filter { it == CameraMoveReason.Gesture }
            .collect { currentOnMapPan() }
    }
    FollowVehicle(
        mapState = mapState,
        vehicle = state.vehicle,
        following = state.following,
        insets = cameraInsets,
        style = baseStyle,
    )
    MaplibreMap(
        modifier = modifier.fillMaxSize(),
        state = mapState,
        viewportInsets = cameraInsets,
        overlay = {
            state.home?.let { HomeMarker(modifier = Modifier.placedAt(it.toPosition())) }
            state.vehicle?.let { vehicle ->
                VehicleMarker(
                    vehicle = vehicle,
                    mapBearing = mapState.cameraPosition.bearing,
                    home = homeFromVehicle(mapState, vehicle, state.home),
                )
            }
            MapControls(
                mapState = mapState,
                vehicle = state.vehicle,
                insets = viewportInsets,
                onRecenter = onRecenter,
                onBottomCoverChange = { controlsCovered = it },
            )
        },
    )
}

/**
 * Keeps the vehicle at the centre of the visible map while the camera follows it, zooming in on the first fix. Each
 * move runs to its end before the next one, towards the latest position: restarting it for every position, five a
 * second, cancelled each move before the camera went anywhere, so a moving vehicle was left behind (seen in SITL). A
 * move is also cancelled when the insets or the [style] change, so following starts again then. Within
 * [FOLLOW_DEAD_ZONE] of the centre the camera stays, so a GPS's noise at rest never shakes the map.
 */
@Composable
private fun FollowVehicle(
    mapState: MapState,
    vehicle: MapVehicle?,
    following: Boolean,
    insets: PaddingValues,
    style: BaseStyle,
) {
    val position by rememberUpdatedState(vehicle?.position)
    LaunchedEffect(mapState, following, insets, style) {
        if (!following) return@LaunchedEffect
        mapState.awaitViewport()
        var restarted = true
        snapshotFlow { position }.filterNotNull().conflate().collect { target ->
            val zoom = FOLLOW_ZOOM.takeIf { mapState.cameraPosition.zoom < MIN_FOLLOW_ZOOM }
            // The first move after a restart re-centres for the new insets or style, however small.
            val centre = mapState.screenLocationFromPosition(mapState.cameraPosition.center)
            val vehicleOnScreen = mapState.screenLocationFromPosition(target.toPosition())
            val still = centre != null && vehicleOnScreen != null && withinDeadZone(centre, vehicleOnScreen)
            if (!restarted && zoom == null && still) return@collect
            restarted = false
            mapState.animateCamera(
                update = CameraUpdate(center = target.toPosition(), zoom = zoom),
                animation = CameraAnimation.Ease { duration = FOLLOW_STEP },
            )
        }
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

/** Where home is on screen from the vehicle's centre, `null` while either is not on the map. */
private fun homeFromVehicle(mapState: MapState, vehicle: MapVehicle, home: MapPoint?): DpOffset? {
    val vehicleOnScreen = mapState.screenLocationFromPosition(vehicle.position.toPosition())
    val homeOnScreen = home?.let { mapState.screenLocationFromPosition(it.toPosition()) }
    if (vehicleOnScreen == null || homeOnScreen == null) return null
    return DpOffset(homeOnScreen.x - vehicleOnScreen.x, homeOnScreen.y - vehicleOnScreen.y)
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

private val STYLE_TIMEOUT = 10.seconds
