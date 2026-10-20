package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-029")
class PositionTrustTest {

    private val clock = TestTimeSource()

    private lateinit var staleness: StalenessRule

    @BeforeEach
    fun setUp() {
        staleness = StalenessRule()
    }

    @Test
    fun `GIVEN each fix type WHEN judging the position THEN only a fresh 3D fix verifies it`() {
        val fixTypes = listOf(
            GpsFixType.NoGps,
            GpsFixType.NoFix,
            GpsFixType.Fix2d,
            GpsFixType.Fix3d,
            GpsFixType.Dgps,
            GpsFixType.RtkFloat,
            GpsFixType.RtkFixed,
            GpsFixType.Static,
            GpsFixType.Ppp,
            GpsFixType.Unknown(UNDEFINED_FIX_TYPE),
        )

        val result = fixTypes.associateWith { telemetry(gps(it)).isPositionVerified(staleness) }

        result shouldBeEqualTo fixTypes.associateWith { it in FIX_3D }
    }

    @Test
    fun `GIVEN ArduPilot's boot position and no GPS fix WHEN judging the position THEN is not verified`() {
        val telemetry = telemetry(gps(GpsFixType.NoFix)).copy(position = Timed(NULL_ISLAND, clock.markNow()))

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN ArduPilot's boot position with a 3D fix WHEN judging the position THEN is not verified`() {
        val telemetry = telemetry(gps(GpsFixType.Fix3d)).copy(position = Timed(NULL_ISLAND, clock.markNow()))

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a position and no GPS received yet WHEN judging the position THEN is not verified`() {
        val telemetry = Telemetry(position = Timed(NULL_ISLAND, clock.markNow()), origin = DataOrigin.LIVE)

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a fresh 3D fix and no position received yet WHEN judging the position THEN is not verified`() {
        val telemetry = Telemetry(gps = Timed(gps(GpsFixType.Fix3d), clock.markNow()), origin = DataOrigin.LIVE)

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a 3D fix on the equator or the prime meridian WHEN judging the position THEN is verified`() {
        val positions = listOf(EQUATOR, PRIME_MERIDIAN)

        val result = positions.map { telemetry(gps(GpsFixType.Fix3d), it).isPositionVerified(staleness) }

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN a 3D fix 1 ms under the GPS threshold WHEN judging the position THEN is verified`() {
        val telemetry = telemetry(gps(GpsFixType.Fix3d))
        clock += GPS_STALE_AFTER - 1.milliseconds

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a 3D fix exactly at the GPS threshold WHEN judging the position THEN is not verified`() {
        val telemetry = telemetry(gps(GpsFixType.Fix3d))
        clock += GPS_STALE_AFTER

        val result = telemetry.isPositionVerified(staleness)

        result.shouldBeFalse()
    }

    /** [position] and [gps], both received now. */
    private fun telemetry(gps: GpsStatus, position: GlobalPosition = CANBERRA): Telemetry {
        val now = clock.markNow()
        return Telemetry(position = Timed(position, now), gps = Timed(gps, now), origin = DataOrigin.LIVE)
    }
}

private val GPS_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.GPS)

/** `GPS_FIX_TYPE` ends at 8 (`GPS_FIX_TYPE_PPP`) in `common.xml`. */
private const val UNDEFINED_FIX_TYPE = 9

/** Every `GPS_FIX_TYPE` with a 3D position, from `common.xml`: written out, not taken from the code under test. */
private val FIX_3D = setOf(
    GpsFixType.Fix3d,
    GpsFixType.Dgps,
    GpsFixType.RtkFloat,
    GpsFixType.RtkFixed,
    GpsFixType.Static,
    GpsFixType.Ppp,
)

private val CANBERRA = position(latitude = -35.3633514, longitude = 149.1652411)

/** What ArduPilot SITL sent before its first GPS fix (#42): lat 0, lon 0, relative_alt 0. */
private val NULL_ISLAND = position(latitude = 0.0, longitude = 0.0)

/** Only one coordinate at 0: real places, unlike ArduPilot's uninitialised 0°, 0°. */
private val EQUATOR = position(latitude = 0.0, longitude = 32.5)
private val PRIME_MERIDIAN = position(latitude = 51.4779, longitude = 0.0)

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
