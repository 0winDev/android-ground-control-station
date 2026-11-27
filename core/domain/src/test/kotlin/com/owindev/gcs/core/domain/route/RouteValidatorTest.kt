package com.owindev.gcs.core.domain.route

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.route.RouteItem.Land
import com.owindev.gcs.core.domain.route.RouteItem.ReturnToLaunch
import com.owindev.gcs.core.domain.route.RouteItem.TakeOff
import com.owindev.gcs.core.domain.route.RouteItem.Waypoint
import com.owindev.gcs.core.domain.route.RouteViolation.AltitudeOutOfRange
import com.owindev.gcs.core.domain.route.RouteViolation.FirstNotTakeOff
import com.owindev.gcs.core.domain.route.RouteViolation.HoldOutOfRange
import com.owindev.gcs.core.domain.route.RouteViolation.LastNotReturnOrLand
import com.owindev.gcs.core.domain.route.RouteViolation.LegOutsideGeofence
import com.owindev.gcs.core.domain.route.RouteViolation.MisplacedEnd
import com.owindev.gcs.core.domain.route.RouteViolation.MisplacedTakeOff
import com.owindev.gcs.core.domain.route.RouteViolation.OutsideGeofence
import com.owindev.gcs.core.domain.route.RouteViolation.TooManyItems
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.SystemId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-055")
class RouteValidatorTest {

    private val clock = TestTimeSource()

    private lateinit var validator: RouteValidator

    @BeforeEach
    fun setUp() {
        validator = RouteValidator(clock)
    }

    @Test
    fun `GIVEN a route within every limit WHEN judging it THEN is valid`() {
        val route = route(takeOff(), waypoint(NEAR), waypoint(EAST_EDGE_INSIDE), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @ParameterizedTest
    @ValueSource(doubles = [2.0, 120.0])
    fun `GIVEN altitudes at 2 m and 120 m WHEN judging the route THEN is valid`(altitude: Double) {
        val route = route(takeOff(altitude), waypoint(NEAR, altitude = altitude), Land)

        val result = validator.judge(route, VEHICLE, freshHome())

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.99, 120.01, Double.NaN, Double.POSITIVE_INFINITY])
    fun `GIVEN a waypoint's altitude outside 2 to 120 m WHEN judging the route THEN that waypoint is out of range`(
        altitude: Double,
    ) {
        val route = route(takeOff(), waypoint(NEAR, altitude = altitude), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(AltitudeOutOfRange(1)))
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.99, 120.01, Double.NaN])
    fun `GIVEN a take off's altitude outside 2 to 120 m WHEN judging the route THEN the take off is out of range`(
        altitude: Double,
    ) {
        val route = route(takeOff(altitude), waypoint(NEAR), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(AltitudeOutOfRange(0)))
    }

    @Test
    fun `GIVEN a valid route WHEN judging it THEN the verdict names the home and the time it was judged on`() {
        val telemetry = freshHome()
        clock += 1.seconds
        val judgedAt = clock.markNow()

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result shouldBeEqualTo RouteVerdict.Valid(checkNotNull(telemetry.getValue(VEHICLE).home), judgedAt)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 65_535.0])
    fun `GIVEN a hold of 0 s or 65535 s WHEN judging the route THEN is valid`(holdSeconds: Double) {
        val route = route(takeOff(), waypoint(NEAR, holdSeconds = holdSeconds), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @ParameterizedTest
    @ValueSource(doubles = [-0.01, 65_535.01, Double.NaN, Double.POSITIVE_INFINITY])
    fun `GIVEN a hold outside 0 to 65535 s WHEN judging the route THEN that waypoint's hold is out of range`(
        holdSeconds: Double,
    ) {
        val route = route(takeOff(), waypoint(NEAR, holdSeconds = holdSeconds), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(HoldOutOfRange(1)))
    }

    @Test
    fun `GIVEN a first waypoint 1 cm past the geofence WHEN judging THEN it and its leg from home are outside`() {
        val route = route(takeOff(), waypoint(EAST_EDGE_OUTSIDE), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @Test
    fun `GIVEN a later waypoint past the geofence WHEN judging THEN it and the legs at its ends are outside`() {
        val route = route(takeOff(), waypoint(NEAR), waypoint(NORTH_EDGE_OUTSIDE), waypoint(NEAR), Land)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(
            listOf(OutsideGeofence(2), LegOutsideGeofence(1, 2), LegOutsideGeofence(2, 3)),
        )
    }

    @Test
    fun `GIVEN a leg between two waypoints 1 cm inside the edge WHEN judging the route THEN is valid`() {
        val route = route(takeOff(), waypoint(EAST_EDGE_INSIDE), waypoint(NORTH_EDGE_INSIDE), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @Test
    fun `GIVEN a smaller geofence WHEN judging a waypoint past it THEN it is outside`() {
        val route = route(takeOff(), waypoint(NEAR), ReturnToLaunch, radiusMetres = SMALL_RADIUS)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["NaN, 149.1652374", "-35.3632621, NaN"])
    fun `GIVEN a waypoint not a number WHEN judging the route THEN it is outside`(position: String) {
        val (latitude, longitude) = position.split(", ").map(String::toDouble)
        val route = route(takeOff(), waypoint(latitude to longitude), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @Test
    fun `GIVEN a waypoint inside that rounds 1 mm past the edge WHEN judging THEN it and its leg are outside`() {
        val route = route(takeOff(), waypoint(ROUNDS_OUTSIDE), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @Test
    fun `GIVEN a longitude one turn past home's WHEN judging the route THEN it is outside`() {
        val route = route(takeOff(), waypoint(HOME.first to HOME.second + 360), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @Test
    fun `GIVEN a latitude just past 90 degrees 11 m from home WHEN judging the route THEN it is outside`() {
        val route = route(takeOff(), waypoint(PAST_NORTH_POLE), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome(home = NEAR_NORTH_POLE))

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(OutsideGeofence(1), LegOutsideGeofence(null, 1)))
    }

    @Test
    fun `GIVEN 160 items WHEN judging the route THEN is valid`() {
        val route = route(takeOff(), *Array(158) { waypoint(NEAR) }, ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @Test
    fun `GIVEN 161 items WHEN judging the route THEN it has too many`() {
        val route = route(takeOff(), *Array(159) { waypoint(NEAR) }, ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(TooManyItems(161)))
    }

    @Test
    fun `GIVEN no items WHEN judging the route THEN it is empty`() {
        val route = route()

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(RouteViolation.Empty))
    }

    @Test
    fun `GIVEN a geofence 1 cm wider than 500 m WHEN judging the route THEN the geofence is too large`() {
        val route = route(takeOff(), waypoint(NEAR), ReturnToLaunch, radiusMetres = 500.01)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(RouteViolation.GeofenceTooLarge))
    }

    @Test
    fun `GIVEN a take off after the first item WHEN judging the route THEN that take off is misplaced`() {
        val route = route(takeOff(), waypoint(NEAR), takeOff(), waypoint(NEAR), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(MisplacedTakeOff(2)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["RTL", "LAND"])
    fun `GIVEN an RTL or a land before the last item WHEN judging the route THEN that item is misplaced`(end: String) {
        val middle = if (end == "RTL") ReturnToLaunch else Land
        val route = route(takeOff(), waypoint(NEAR), middle, waypoint(NEAR), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(MisplacedEnd(2)))
    }

    @Test
    fun `GIVEN a land before a misplaced take off WHEN judging the route THEN the take off is listed first`() {
        val route = route(takeOff(), Land, takeOff(), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(MisplacedTakeOff(2), MisplacedEnd(1)))
    }

    @Test
    fun `GIVEN a route starting with a waypoint WHEN judging it THEN its first item is not a take off`() {
        val route = route(waypoint(NEAR), ReturnToLaunch)

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(FirstNotTakeOff(0)))
    }

    @Test
    fun `GIVEN a route ending with a waypoint WHEN judging it THEN its last item is not an RTL or a land`() {
        val route = route(takeOff(), waypoint(NEAR))

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(listOf(LastNotReturnOrLand(1)))
    }

    @Test
    fun `GIVEN several violations WHEN judging the route THEN each is listed with its item in order`() {
        val route = route(
            waypoint(EAST_EDGE_OUTSIDE, altitude = 0.0, holdSeconds = -1.0),
            takeOff(),
            waypoint(NEAR, altitude = 121.0),
        )

        val result = validator.judge(route, VEHICLE, freshHome())

        result shouldBeEqualTo RouteVerdict.Invalid(
            listOf(
                FirstNotTakeOff(0),
                LastNotReturnOrLand(2),
                MisplacedTakeOff(1),
                AltitudeOutOfRange(0),
                AltitudeOutOfRange(2),
                HoldOutOfRange(0),
                OutsideGeofence(0),
                LegOutsideGeofence(null, 0),
                LegOutsideGeofence(0, 2),
            ),
        )
    }

    @Test
    fun `GIVEN a home just under 3 s old WHEN judging the route THEN is judged`() {
        val telemetry = freshHome()
        clock += MAX_HOME_AGE - 1.milliseconds

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result.shouldBeInstanceOf<RouteVerdict.Valid>()
    }

    @Test
    fun `GIVEN a home exactly 3 s old WHEN judging the route THEN is not judged as stale`() {
        val telemetry = freshHome()
        clock += MAX_HOME_AGE

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result shouldBeEqualTo RouteVerdict.NotJudged(NotJudgedReason.STALE_HOME)
    }

    @Test
    fun `GIVEN the vehicle's telemetry without a home WHEN judging the route THEN is not judged`() {
        val telemetry = mapOf(VEHICLE to Telemetry(origin = DataOrigin.LIVE))

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result shouldBeEqualTo RouteVerdict.NotJudged(NotJudgedReason.NO_HOME)
    }

    @Test
    fun `GIVEN only another vehicle's fresh home WHEN judging the route THEN is not judged`() {
        val telemetry = freshHome(systemId = OTHER_VEHICLE)

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result shouldBeEqualTo RouteVerdict.NotJudged(NotJudgedReason.NO_HOME)
    }

    @Test
    fun `GIVEN a fresh home from a replay WHEN judging the route THEN is not judged`() {
        val telemetry = freshHome(origin = DataOrigin.REPLAY)

        val result = validator.judge(validRoute(), VEHICLE, telemetry)

        result shouldBeEqualTo RouteVerdict.NotJudged(NotJudgedReason.NOT_LIVE)
    }

    private fun freshHome(
        systemId: SystemId = VEHICLE,
        origin: DataOrigin = DataOrigin.LIVE,
        home: Pair<Double, Double> = HOME,
    ): Map<SystemId, Telemetry> {
        val home = Home(latitude = home.first, longitude = home.second, altitudeMsl = HOME_ALTITUDE_MSL)
        return mapOf(systemId to Telemetry(origin = origin, home = Timed(home, clock.markNow())))
    }

    private fun validRoute(): Route = route(takeOff(), waypoint(NEAR), ReturnToLaunch)

    private fun route(vararg items: RouteItem, radiusMetres: Double = RouteLimits.DEFAULT_GEOFENCE_RADIUS_METRES) =
        Route(items.toList(), radiusMetres)

    private fun takeOff(altitude: Double = ALTITUDE): TakeOff = TakeOff(altitude)

    private fun waypoint(
        position: Pair<Double, Double>,
        altitude: Double = ALTITUDE,
        holdSeconds: Double = HOLD_SECONDS,
    ): Waypoint = Waypoint(position.first, position.second, altitude, holdSeconds)

    private companion object {
        val VEHICLE = SystemId(1)
        val OTHER_VEHICLE = SystemId(2)
        const val ALTITUDE = 10.0
        const val HOLD_SECONDS = 3.0
        const val HOME_ALTITUDE_MSL = 584.0
        const val SMALL_RADIUS = 99.99
        val MAX_HOME_AGE = 3.seconds

        // SITL's home and points from tools/geo/route_reference.py (GeographicLib 2.1, rounded to 1e-7 degrees): their
        // distances from home are in the comments, each 1 cm or more off the 500 m edge (#40).
        val HOME = -35.3632621 to 149.1652374
        val NEAR = -35.3623608 to 149.1652374 // 99.997 m north
        val EAST_EDGE_INSIDE = -35.3632620 to 149.1707389 // 499.992 m east
        val EAST_EDGE_OUTSIDE = -35.3632620 to 149.1707391 // 500.010 m east
        val NORTH_EDGE_INSIDE = -35.3587555 to 149.1652374 // 499.995 m north
        val NORTH_EDGE_OUTSIDE = -35.3587554 to 149.1652374 // 500.006 m north
        val NEAR_NORTH_POLE = 89.9999 to 0.0

        // 499.9995 m east, rounded half up to 149.1707390 at 500.0013 m.
        val ROUNDS_OUTSIDE = -35.3632620 to 149.17073898
        val PAST_NORTH_POLE = 90.0000001 to 0.0 // 11 m north of NEAR_NORTH_POLE, off the globe
    }
}
