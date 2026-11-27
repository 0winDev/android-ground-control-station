package com.owindev.gcs.core.domain.route

/**
 * A route the vehicle flies on its own, without home, which the vehicle holds as its item 0: its [items] in order and
 * the radius in m of its geofence circle around home. Whether it may be flown is judged by [RouteValidator] (REQ-055).
 */
data class Route(
    val items: List<RouteItem>,
    val geofenceRadiusMetres: Double = RouteLimits.DEFAULT_GEOFENCE_RADIUS_METRES,
) {

    init {
        require(geofenceRadiusMetres.isFinite() && geofenceRadiusMetres > 0.0) {
            "The geofence radius must be positive, was $geofenceRadiusMetres m"
        }
    }
}

/** One item of a [Route]; altitudes are above home, in m, as `MAV_FRAME_GLOBAL_RELATIVE_ALT_INT` sends them. */
sealed interface RouteItem {

    /** NAV_TAKEOFF: climb where the vehicle stands. */
    data class TakeOff(val altitudeMetres: Double) : RouteItem

    /** NAV_WAYPOINT: fly to a WGS84 position in degrees, then hold there for [holdSeconds]. */
    data class Waypoint(
        val latitude: Double,
        val longitude: Double,
        val altitudeMetres: Double,
        val holdSeconds: Double,
    ) : RouteItem {

        /**
         * [latitude] and [longitude] as MISSION_ITEM_INT's `x` and `y` carry them, in degrees × 10⁷ rounded half up:
         * the position the vehicle gets, which the route is judged on. Meaningful only within ±90° and ±180°.
         */
        val latitudeE7: Long get() = Math.round(latitude * DEGREES_E7)
        val longitudeE7: Long get() = Math.round(longitude * DEGREES_E7)

        companion object {
            const val DEGREES_E7 = 1e7
        }
    }

    /** NAV_RETURN_TO_LAUNCH: fly back to home and land. */
    data object ReturnToLaunch : RouteItem

    /** NAV_LAND: land where the vehicle is. */
    data object Land : RouteItem
}
