package com.owindev.gcs.core.domain.route

import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.Timed
import kotlin.time.ComparableTimeMark

/** What [RouteValidator] says of a route (REQ-055): only [Valid] lets it be uploaded or started. */
sealed interface RouteVerdict {

    /**
     * Valid against [home], as received, when judged at [judgedAt]. It holds only for the send it gates: the upload
     * (#152) and the start (#155) judge again under the sender's lock and send only while the vehicle's home is still
     * [home], the same value with the same arrival.
     */
    data class Valid(val home: Timed<Home>, val judgedAt: ComparableTimeMark) : RouteVerdict

    /** Every violation found, in the order [RouteValidator] lists them. */
    data class Invalid(val violations: List<RouteViolation>) : RouteVerdict

    /** The route could not be judged, so it is not valid. */
    data class NotJudged(val reason: NotJudgedReason) : RouteVerdict
}

enum class NotJudgedReason {
    /** The active vehicle has sent no home, or forgot it on a reboot. */
    NO_HOME,

    /** Its last home is [RouteLimits.MAX_HOME_AGE] old or older. */
    STALE_HOME,

    /** Its telemetry comes from a replay, not from the vehicle now. */
    NOT_LIVE,
}

/**
 * One reason a route is invalid. An `item` is the index in [Route.items]; the vehicle holds it as seq `item + 1`, after
 * home.
 */
sealed interface RouteViolation {

    data object Empty : RouteViolation

    data class TooManyItems(val count: Int) : RouteViolation

    /** The route's geofence radius is above [RouteLimits.MAX_GEOFENCE_RADIUS_METRES]. */
    data object GeofenceTooLarge : RouteViolation

    data class FirstNotTakeOff(val item: Int) : RouteViolation

    data class LastNotReturnOrLand(val item: Int) : RouteViolation

    /** A take off anywhere but first. */
    data class MisplacedTakeOff(val item: Int) : RouteViolation

    /** An RTL or a land anywhere but last. */
    data class MisplacedEnd(val item: Int) : RouteViolation

    data class AltitudeOutOfRange(val item: Int) : RouteViolation

    data class HoldOutOfRange(val item: Int) : RouteViolation

    data class OutsideGeofence(val item: Int) : RouteViolation

    /** The leg into waypoint [to] from waypoint [from], or from home when [from] is `null`. */
    data class LegOutsideGeofence(val from: Int?, val to: Int) : RouteViolation
}
