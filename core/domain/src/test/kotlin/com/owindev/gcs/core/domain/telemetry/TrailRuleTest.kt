package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-035")
class TrailRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: TrailRule

    @BeforeEach
    fun setUp() {
        rule = TrailRule(StalenessRule())
    }

    @Test
    fun `GIVEN no trail WHEN a verified position arrives THEN starts the first segment with it`() {
        val telemetry = verified(EAST_0)

        val result = rule.after(Trail.EMPTY, previousPosition = null, telemetry)

        result shouldBeEqualTo Trail(listOf(listOf(EAST_0.point)), open = true)
    }

    @Test
    fun `GIVEN an open segment WHEN a position arrives without a 3D fix, fresh GPS or a real place THEN closes it`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())
        val unverified = listOf(
            Telemetry(position = Timed(EAST_1, clock.markNow()), origin = DataOrigin.LIVE),
            withGps(EAST_1, GpsFixType.Fix2d),
            verified(NULL_ISLAND),
            staleGps(EAST_1),
        )

        val result = unverified.map { rule.after(trail, previous, it) }

        result shouldBeEqualTo List(unverified.size) { Trail(listOf(listOf(EAST_0.point)), open = false) }
    }

    @Test
    fun `GIVEN an open segment WHEN the same frozen position arrives again THEN the trail is unchanged`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())

        val result = rule.after(trail, previous, verified(EAST_0))

        result shouldBe trail
    }

    @Test
    fun `GIVEN an open segment WHEN a position 1·9 m away arrives THEN the trail is unchanged`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())

        val result = rule.after(trail, previous, verified(SOUTH_1_9_M))

        result shouldBe trail
    }

    @Test
    fun `GIVEN an open segment WHEN a position 2·1 m away arrives THEN extends it`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())

        val result = rule.after(trail, previous, verified(EAST_1))

        result shouldBeEqualTo Trail(listOf(listOf(EAST_0.point, EAST_1.point)), open = true)
    }

    @Test
    fun `GIVEN a segment closed by an unverified position WHEN a verified one arrives THEN starts a second segment`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = false)
        val previous = Timed(EAST_1, clock.markNow())

        val result = rule.after(trail, previous, verified(EAST_2))

        result shouldBeEqualTo Trail(listOf(listOf(EAST_0.point), listOf(EAST_2.point)), open = true)
    }

    @Test
    fun `GIVEN no position for longer than its staleness threshold WHEN one arrives THEN starts a second segment`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())
        clock += POSITION_STALE_AFTER + HALF_SECOND

        val result = rule.after(trail, previous, verified(EAST_1))

        result shouldBeEqualTo Trail(listOf(listOf(EAST_0.point), listOf(EAST_1.point)), open = true)
    }

    @Test
    fun `GIVEN the last position half a second ago WHEN the next arrives THEN extends the segment`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val previous = Timed(EAST_0, clock.markNow())
        clock += HALF_SECOND

        val result = rule.after(trail, previous, verified(EAST_1))

        result shouldBeEqualTo Trail(listOf(listOf(EAST_0.point, EAST_1.point)), open = true)
    }

    @Test
    fun `GIVEN a full trail WHEN a point is added THEN the oldest point of the first segment is dropped`() {
        val first = eastward(from = 0, count = 4_000)
        val second = eastward(from = 4_000, count = 1_000)
        val trail = Trail(listOf(first, second), open = true)
        val next = eastPosition(5_000)

        val result = rule.after(trail, Timed(eastPosition(4_999), clock.markNow()), verified(next))

        result shouldBeEqualTo Trail(listOf(first.drop(1), second + next.point), open = true)
    }

    @Test
    fun `GIVEN a full trail whose first segment has one point WHEN a point is added THEN that segment disappears`() {
        val second = eastward(from = 1, count = 4_999)
        val trail = Trail(listOf(listOf(EAST_0.point), second), open = true)
        val next = eastPosition(5_000)

        val result = rule.after(trail, Timed(eastPosition(4_999), clock.markNow()), verified(next))

        result shouldBeEqualTo Trail(listOf(second + next.point), open = true)
    }

    @Test
    fun `GIVEN an open segment on one side of 180 degrees WHEN a position 2·2 m across arrives THEN extends it`() {
        val trail = Trail(listOf(listOf(WEST_OF_180.point)), open = true)
        val previous = Timed(WEST_OF_180, clock.markNow())

        val result = rule.after(trail, previous, verified(EAST_OF_180))

        result shouldBeEqualTo Trail(listOf(listOf(WEST_OF_180.point, EAST_OF_180.point)), open = true)
    }

    @Test
    fun `GIVEN GPS but no position yet WHEN judging the trail THEN the trail is unchanged`() {
        val trail = Trail(listOf(listOf(EAST_0.point)), open = true)
        val telemetry = Telemetry(gps = Timed(gps(GpsFixType.Fix3d), clock.markNow()), origin = DataOrigin.LIVE)

        val result = rule.after(trail, previousPosition = null, telemetry)

        result shouldBe trail
    }

    @Test
    fun `GIVEN no spacing WHEN building the rule THEN is refused`() {
        val creating = { TrailRule(StalenessRule(), minSpacingMetres = 0.0) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN no room for a point WHEN building the rule THEN is refused`() {
        val creating = { TrailRule(StalenessRule(), maxPoints = 0) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    private fun verified(position: GlobalPosition) = withGps(position, GpsFixType.Fix3d)

    private fun withGps(position: GlobalPosition, fixType: GpsFixType): Telemetry {
        val now = clock.markNow()
        return Telemetry(position = Timed(position, now), gps = Timed(gps(fixType), now), origin = DataOrigin.LIVE)
    }

    private fun staleGps(position: GlobalPosition): Telemetry {
        val gpsAt = clock.markNow() - GPS_STALE_AFTER
        return Telemetry(
            position = Timed(position, clock.markNow()),
            gps = Timed(gps(GpsFixType.Fix3d), gpsAt),
            origin = DataOrigin.LIVE,
        )
    }
}

private val POSITION_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.POSITION)
private val GPS_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.GPS)
private val HALF_SECOND = 500.milliseconds

private const val CANBERRA_LATITUDE = -35.3633514
private const val CANBERRA_LONGITUDE = 149.1652411

/** 2.1038 m east at Canberra's latitude on the trail's sphere (GeodSolve, `GreatCircleTest`): clear of the 2 m edge. */
private const val EAST_STEP_DEGREES = 0.0000232

private val EAST_0 = eastPosition(0)
private val EAST_1 = eastPosition(1)
private val EAST_2 = eastPosition(2)

/** 1.9014 m south of [EAST_0] (GeodSolve, `GreatCircleTest`). */
private val SOUTH_1_9_M = position(latitude = -35.3633685, longitude = CANBERRA_LONGITUDE)

/** 2.19 m apart across 180° (GeodSolve, `GreatCircleTest`). */
private val WEST_OF_180 = position(latitude = 10.0, longitude = 179.99999)
private val EAST_OF_180 = position(latitude = 10.0, longitude = -179.99999)

/** What ArduPilot SITL sent before its first GPS fix (#42). */
private val NULL_ISLAND = position(latitude = 0.0, longitude = 0.0)

private val GlobalPosition.point get() = TrailPoint(latitude, longitude)

private fun eastPosition(step: Int) =
    position(latitude = CANBERRA_LATITUDE, longitude = CANBERRA_LONGITUDE + step * EAST_STEP_DEGREES)

private fun eastward(from: Int, count: Int): List<TrailPoint> = (from until from + count).map { eastPosition(it).point }

private fun position(latitude: Double, longitude: Double) = GlobalPosition(
    latitude = latitude,
    longitude = longitude,
    altitudeMsl = 0.0,
    altitudeAboveHome = 0.0,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = null,
)

private fun gps(fixType: GpsFixType) = GpsStatus(
    fixType = fixType,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)
