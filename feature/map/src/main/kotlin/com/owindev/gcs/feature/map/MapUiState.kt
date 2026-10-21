package com.owindev.gcs.feature.map

import kotlin.time.Duration

/**
 * What the map shows (REQ-034): the active vehicle, its home and its trail, and whether the camera follows the
 * vehicle. Each part is `null` or empty until it is known.
 */
data class MapUiState(
    val vehicle: MapVehicle? = null,
    val home: MapPoint? = null,
    val trail: List<List<MapPoint>> = emptyList(),
    val following: Boolean = true,
)

/** A place on the map, in degrees, WGS84. */
data class MapPoint(val latitude: Double, val longitude: Double)

/** The active vehicle where it was last reported, and how far that position can be trusted ([shown]). */
data class MapVehicle(
    val systemId: Int,
    val position: MapPoint,
    val heading: Double?,
    val altitudeAboveHome: Double,
    val shown: PositionShown,
)

/** How the vehicle's position is drawn: [Live] only when it can be trusted now; every other case greyed. */
sealed interface PositionShown {

    data object Live : PositionShown

    /** Fresh, but without a fresh 3D fix behind it (REQ-029). */
    data object NotVerified : PositionShown

    /** No position for longer than its staleness threshold (REQ-026); [age] as the HUD shows ages. */
    data class Stale(val age: Duration) : PositionShown

    /** The vehicle's link is lost (REQ-017); [age] is the last position's, as the HUD shows ages. */
    data class LinkLost(val age: Duration) : PositionShown
}
