package com.owindev.gcs.core.domain.route

import com.owindev.gcs.core.domain.geo.LocalPlane
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.route.RouteItem.Land
import com.owindev.gcs.core.domain.route.RouteItem.ReturnToLaunch
import com.owindev.gcs.core.domain.route.RouteItem.TakeOff
import com.owindev.gcs.core.domain.route.RouteItem.Waypoint
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.SystemId
import kotlin.time.TimeSource

/**
 * Judges a route against the active vehicle's current home (REQ-055), before it is uploaded and again right before it
 * starts, since ArduCopter sets home again where it arms.
 *
 * Only a home that the vehicle's live link sent under [RouteLimits.MAX_HOME_AGE] ago lets the route be judged at all.
 * Then it must not be empty, hold at most [RouteLimits.MAX_ITEMS] items, have a geofence of at most
 * [RouteLimits.MAX_GEOFENCE_RADIUS_METRES], take off first and nowhere else, and end with an RTL or a land and only
 * there; every take off and waypoint altitude must lie within [RouteLimits.MIN_ALTITUDE_METRES]..
 * [RouteLimits.MAX_ALTITUDE_METRES] above home, every hold within 0..[RouteLimits.MAX_HOLD_SECONDS], and every
 * waypoint and every leg inside the geofence circle around home, at the position the upload sends (degrees × 10⁷).
 *
 * A leg's farthest point from home is always one of its ends, since a circle is convex: a leg is inside when both ends
 * are. The legs are home to the first waypoint, then each waypoint to the next; a take off and a land stay where the
 * vehicle is, and the RTL's leg ends at home, the centre. A waypoint off the globe (a latitude past ±90°, a longitude
 * past ±180°, not a number) is outside. Violations are listed by kind in the order of [RouteViolation], each kind in
 * item order.
 *
 * [timeSource] must be the one home's arrival is marked on, so [RouteVerdict.Valid.judgedAt] compares with it.
 */
class RouteValidator(private val timeSource: TimeSource.WithComparableMarks) {

    fun judge(route: Route, vehicle: SystemId, telemetry: Map<SystemId, Telemetry>): RouteVerdict {
        val known = telemetry[vehicle]
        val home = known?.home?.takeIf { known.origin == DataOrigin.LIVE && isFresh(it) }
            ?: return RouteVerdict.NotJudged(notJudgedReason(known))
        val violations = shapeViolations(route) +
            itemViolations(route, home.value) +
            legViolations(route, home.value)
        return if (violations.isEmpty()) {
            RouteVerdict.Valid(home, judgedAt = timeSource.markNow())
        } else {
            RouteVerdict.Invalid(violations)
        }
    }

    private fun isFresh(home: Timed<Home>): Boolean = home.receivedAt.elapsedNow() < RouteLimits.MAX_HOME_AGE

    private fun notJudgedReason(known: Telemetry?): NotJudgedReason = when {
        known == null -> NotJudgedReason.NO_HOME
        known.origin != DataOrigin.LIVE -> NotJudgedReason.NOT_LIVE
        known.home == null -> NotJudgedReason.NO_HOME
        else -> NotJudgedReason.STALE_HOME
    }

    private fun shapeViolations(route: Route): List<RouteViolation> {
        val items = route.items
        val indexed = items.withIndex()
        val misplacedTakeOffs = indexed.filter { (index, item) -> item is TakeOff && index != 0 }
            .map { RouteViolation.MisplacedTakeOff(it.index) }
        val misplacedEnds = indexed.filter { (index, item) -> item.isEnd() && index != items.lastIndex }
            .map { RouteViolation.MisplacedEnd(it.index) }
        return listOfNotNull(
            RouteViolation.Empty.takeIf { items.isEmpty() },
            RouteViolation.TooManyItems(items.size).takeIf { items.size > RouteLimits.MAX_ITEMS },
            RouteViolation.GeofenceTooLarge.takeIf {
                route.geofenceRadiusMetres > RouteLimits.MAX_GEOFENCE_RADIUS_METRES
            },
            RouteViolation.FirstNotTakeOff(0).takeIf { items.firstOrNull()?.let { it !is TakeOff } == true },
            RouteViolation.LastNotReturnOrLand(items.lastIndex).takeIf { items.lastOrNull()?.isEnd() == false },
        ) + misplacedTakeOffs + misplacedEnds
    }

    private fun itemViolations(route: Route, home: Home): List<RouteViolation> {
        val indexed = route.items.withIndex()
        val altitudes = indexed.mapNotNull { (index, item) ->
            RouteViolation.AltitudeOutOfRange(index).takeIf { item.altitudeMetres()?.let(::isAllowedAltitude) == false }
        }
        val waypoints = route.waypoints()
        val holds = waypoints.mapNotNull { (index, waypoint) ->
            RouteViolation.HoldOutOfRange(index).takeIf { !isAllowedHold(waypoint.holdSeconds) }
        }
        val outside = waypoints.mapNotNull { (index, waypoint) ->
            RouteViolation.OutsideGeofence(index).takeIf { !isInside(waypoint, home, route.geofenceRadiusMetres) }
        }
        return altitudes + holds + outside
    }

    private fun legViolations(route: Route, home: Home): List<RouteViolation> {
        val ends = listOf<IndexedValue<Waypoint>?>(null) + route.waypoints()
        return ends.zipWithNext().mapNotNull { (from, to) ->
            val fromInside = from == null || isInside(from.value, home, route.geofenceRadiusMetres)
            val toInside = isInside(checkNotNull(to).value, home, route.geofenceRadiusMetres)
            RouteViolation.LegOutsideGeofence(from?.index, to.index).takeIf { !(fromInside && toInside) }
        }
    }

    private fun isAllowedAltitude(metres: Double): Boolean =
        metres in RouteLimits.MIN_ALTITUDE_METRES..RouteLimits.MAX_ALTITUDE_METRES

    private fun isAllowedHold(seconds: Double): Boolean = seconds in 0.0..RouteLimits.MAX_HOLD_SECONDS

    private fun isInside(waypoint: Waypoint, home: Home, radiusMetres: Double): Boolean =
        waypoint.latitude in -MAX_LATITUDE..MAX_LATITUDE &&
            waypoint.longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
            LocalPlane.distance(
                home.latitude,
                home.longitude,
                waypoint.latitudeE7 / Waypoint.DEGREES_E7,
                waypoint.longitudeE7 / Waypoint.DEGREES_E7,
            ) <= radiusMetres

    private companion object {
        const val MAX_LATITUDE = 90.0
        const val MAX_LONGITUDE = 180.0
    }
}

private fun Route.waypoints(): List<IndexedValue<Waypoint>> =
    items.withIndex().mapNotNull { (index, item) -> (item as? Waypoint)?.let { IndexedValue(index, it) } }

private fun RouteItem.isEnd(): Boolean = this is ReturnToLaunch || this is Land

private fun RouteItem.altitudeMetres(): Double? = when (this) {
    is TakeOff -> altitudeMetres
    is Waypoint -> altitudeMetres
    ReturnToLaunch, Land -> null
}
