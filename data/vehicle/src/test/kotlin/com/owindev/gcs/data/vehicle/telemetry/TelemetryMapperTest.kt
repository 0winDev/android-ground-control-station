package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.mavlink.MavlinkDecoder
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessages
import com.owindev.gcs.core.mavlink.MavlinkParser
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real and pymavlink-derived frames (`src/test/resources/README.md`). Expected values are the raw values
 * pymavlink decodes, converted by hand: degE7 / 10⁷, mm / 1000, cm and c% / 100, mV / 1000, cA / 100, and
 * radians × 180 / π as Python's `math.degrees` gives it.
 */
@Tag("REQ-026")
class TelemetryMapperTest {

    private lateinit var mapper: TelemetryMapper

    @BeforeEach
    fun setUp() {
        mapper = TelemetryMapper
    }

    @Test
    fun `GIVEN a real GLOBAL_POSITION_INT WHEN mapping it THEN is in degrees, metres and metres per second`() {
        val received = receivedFixture(GLOBAL_POSITION_INT)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(
            position = Timed(
                GlobalPosition(
                    latitude = -35.3633514,
                    longitude = 149.1652411,
                    altitudeMsl = 587.13,
                    altitudeAboveHome = -0.013,
                    velocityNorth = 0.01,
                    velocityEast = -0.01,
                    velocityDown = 0.0,
                    heading = 359.35,
                ),
                RECEIVED_AT,
            ),
            origin = DataOrigin.LIVE,
        )
    }

    @Test
    fun `GIVEN a GLOBAL_POSITION_INT with an unknown heading WHEN mapping it THEN has no heading`() {
        val received = receivedFixture(GLOBAL_POSITION_INT_EDGES)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.position?.value shouldBeEqualTo GlobalPosition(
            latitude = -35.3633514,
            longitude = 149.1652411,
            altitudeMsl = -12.345,
            altitudeAboveHome = -0.5,
            velocityNorth = -3.0,
            velocityEast = 12.34,
            velocityDown = -1.5,
            heading = null,
        )
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a GLOBAL_POSITION_INT with a heading of 360 degrees WHEN mapping it THEN has no heading`() {
        val received = receivedFixture(GLOBAL_POSITION_INT_HDG_36000)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.position?.value shouldBeEqualTo REAL_POSITION.copy(heading = null)
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a GLOBAL_POSITION_INT at the ends of every range WHEN mapping it THEN keeps them`() {
        val received = receivedFixture(GLOBAL_POSITION_INT_ENDS)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.position?.value shouldBeEqualTo REAL_POSITION.copy(
            latitude = 90.0,
            longitude = -180.0,
            heading = 359.99,
        )
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a known position WHEN a GLOBAL_POSITION_INT places the vehicle nowhere THEN the frame is dropped`() {
        val telemetry = Telemetry(position = Timed(REAL_POSITION, EARLIER), origin = DataOrigin.LIVE)
        val placesNowhere = listOf(
            GLOBAL_POSITION_INT_LAT_PAST_90,
            GLOBAL_POSITION_INT_LON_PAST_180,
            GLOBAL_POSITION_INT_LON_PAST_180_EAST,
            GLOBAL_POSITION_INT_LAT_INT32_MIN,
        )

        val result = placesNowhere.map { path -> mapper.update(telemetry, receivedFixture(path), RECEIVED_AT) }

        result shouldBeEqualTo listOf(null, null, null, null)
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a GLOBAL_POSITION_INT at the south and east ends WHEN mapping it THEN keeps them`() {
        val received = receivedFixture(GLOBAL_POSITION_INT_ENDS_SOUTH_EAST)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.position?.value shouldBeEqualTo REAL_POSITION.copy(latitude = -90.0, longitude = 180.0)
    }

    @Test
    fun `GIVEN a real ATTITUDE WHEN mapping it THEN is in degrees`() {
        val received = receivedFixture(ATTITUDE)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(
            attitude = Timed(
                Attitude(roll = -0.06018896765758124, pitch = -0.07352510544512286, yaw = -0.6512406614749974),
                RECEIVED_AT,
            ),
            origin = DataOrigin.LIVE,
        )
    }

    @Test
    fun `GIVEN an ATTITUDE with yaw near minus pi WHEN mapping it THEN is near minus 180 degrees`() {
        val received = receivedFixture(ATTITUDE_EDGES)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.attitude?.value shouldBeEqualTo Attitude(
            roll = 28.64788975654116,
            pitch = -14.32394487827058,
            yaw = -180.00000500895632,
        )
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN an ATTITUDE with a NaN roll and an infinite pitch WHEN mapping it THEN only the yaw is kept`() {
        val received = receivedFixture(ATTITUDE_OUT_OF_RANGE)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.attitude?.value shouldBeEqualTo Attitude(roll = null, pitch = null, yaw = 28.64788975654116)
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN an ATTITUDE with a roll at pi and a yaw past it WHEN mapping it THEN only the yaw is dropped`() {
        val received = receivedFixture(ATTITUDE_PAST_PI)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.attitude?.value shouldBeEqualTo Attitude(
            roll = 180.00000500895632,
            pitch = -14.32394487827058,
            yaw = null,
        )
    }

    @Test
    fun `GIVEN a real truncated VFR_HUD WHEN mapping it THEN is the air data with throttle 0`() {
        val received = receivedFixture(VFR_HUD)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(
            airData = Timed(
                AirData(
                    airspeed = 0.0238723773509264,
                    groundspeed = 0.023871898651123047,
                    climbRate = -0.00015098023868631572,
                    throttle = 0,
                ),
                RECEIVED_AT,
            ),
            origin = DataOrigin.LIVE,
        )
    }

    @Test
    fun `GIVEN a VFR_HUD while descending WHEN mapping it THEN the climb rate is negative`() {
        val received = receivedFixture(VFR_HUD_EDGES)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.airData?.value shouldBeEqualTo AirData(
            airspeed = 0.0238723773509264,
            groundspeed = 0.023871898651123047,
            climbRate = -2.5,
            throttle = 65,
        )
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a VFR_HUD with non-finite floats and throttle 101 WHEN mapping it THEN keeps only the groundspeed`() {
        val received = receivedFixture(VFR_HUD_OUT_OF_RANGE)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.airData?.value shouldBeEqualTo AirData(
            airspeed = null,
            groundspeed = 0.023871898651123047,
            climbRate = null,
            throttle = null,
        )
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a VFR_HUD at full throttle WHEN mapping it THEN keeps a throttle of 100`() {
        val received = receivedFixture(VFR_HUD_THROTTLE_100)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.airData?.value?.throttle shouldBeEqualTo 100
    }

    @Test
    fun `GIVEN a real SYS_STATUS WHEN mapping it THEN is the battery in volts, amperes and percent`() {
        val received = receivedFixture(SYS_STATUS)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(
            battery = Timed(FULL_BATTERY, RECEIVED_AT),
            origin = DataOrigin.LIVE,
        )
    }

    @Test
    fun `GIVEN a SYS_STATUS with distinct readings from an unhealthy monitor WHEN mapping it THEN keeps them`() {
        val received = receivedFixture(SYS_STATUS_EDGES)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo Battery(
            voltage = 11.1,
            current = 15.5,
            remaining = 42,
            monitored = true,
            healthy = false,
        )
    }

    @Test
    fun `GIVEN a SYS_STATUS with every battery reading unknown WHEN mapping it THEN has no reading`() {
        val received = receivedFixture(SYS_STATUS_UNKNOWN_BATTERY)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo UNHEALTHY_WITHOUT_READING
    }

    @Test
    fun `GIVEN the real boot SYS_STATUS, 0 mV and not yet healthy WHEN mapping it THEN has no voltage, not 0 V`() {
        val received = receivedFixture(SYS_STATUS_BOOT)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo UNHEALTHY_WITHOUT_READING
    }

    @Test
    fun `GIVEN a SYS_STATUS with 0 mV from a healthy monitor WHEN mapping it THEN reads 0 V`() {
        val received = receivedFixture(SYS_STATUS_ZERO_VOLTS)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo FULL_BATTERY.copy(voltage = 0.0)
    }

    @Test
    fun `GIVEN a SYS_STATUS without a battery monitor WHEN mapping it THEN is not monitored and has no reading`() {
        val received = receivedFixture(SYS_STATUS_NO_MONITOR)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo Battery(
            voltage = null,
            current = null,
            remaining = null,
            monitored = false,
            healthy = true,
        )
    }

    @Test
    fun `GIVEN a SYS_STATUS after a battery failsafe WHEN mapping it THEN is unhealthy and keeps its voltage`() {
        val received = receivedFixture(SYS_STATUS_UNHEALTHY)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo FULL_BATTERY.copy(healthy = false)
    }

    @Test
    fun `GIVEN a SYS_STATUS with a remaining energy of -128 WHEN mapping it THEN has no remaining energy`() {
        val received = receivedFixture(SYS_STATUS_REMAINING_MIN)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.battery?.value shouldBeEqualTo FULL_BATTERY.copy(remaining = null)
    }

    @Test
    fun `GIVEN a real GPS_RAW_INT WHEN mapping it THEN is the fix with its accuracy in metres and no yaw`() {
        val received = receivedFixture(GPS_RAW_INT)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(
            gps = Timed(
                GpsStatus(
                    fixType = GpsFixType.RtkFixed,
                    satellitesVisible = 10,
                    hdop = 1.21,
                    vdop = 2.0,
                    groundSpeed = 0.0,
                    courseOverGround = 0.0,
                    horizontalAccuracy = 0.3,
                    verticalAccuracy = 0.3,
                    yaw = null,
                ),
                RECEIVED_AT,
            ),
            origin = DataOrigin.LIVE,
        )
    }

    @Test
    fun `GIVEN a GPS_RAW_INT with a distinct value in every field WHEN mapping it THEN yaw north is 0 degrees`() {
        val received = receivedFixture(GPS_RAW_INT_EDGES)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.gps?.value shouldBeEqualTo GpsStatus(
            fixType = GpsFixType.Fix3d,
            satellitesVisible = 12,
            hdop = 1.5,
            vdop = 2.5,
            groundSpeed = 12.34,
            courseOverGround = 359.99,
            horizontalAccuracy = 2309737.967,
            verticalAccuracy = 0.4,
            yaw = 0.0,
        )
    }

    @Test
    fun `GIVEN a GPS_RAW_INT with every value unknown and extensions zero-filled WHEN mapping it THEN is all null`() {
        val received = receivedFixture(GPS_RAW_INT_UNKNOWN)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.gps?.value shouldBeEqualTo GpsStatus(
            fixType = GpsFixType.NoGps,
            satellitesVisible = null,
            hdop = null,
            vdop = null,
            groundSpeed = null,
            courseOverGround = null,
            horizontalAccuracy = null,
            verticalAccuracy = null,
            yaw = null,
        )
    }

    @Test
    fun `GIVEN a GPS_RAW_INT configured for yaw but unable to provide it WHEN mapping it THEN has no yaw`() {
        val received = received(gpsRawInt(yaw = MavlinkMessage.GpsRawInt.YAW_UNAVAILABLE))

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.gps?.value?.yaw.shouldBeNull()
    }

    @Test
    @Tag("REQ-031")
    fun `GIVEN a GPS_RAW_INT with a course and a yaw past their ranges WHEN mapping it THEN has neither`() {
        val received = receivedFixture(GPS_RAW_INT_OUT_OF_RANGE)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.gps?.value shouldBeEqualTo GpsStatus(
            fixType = GpsFixType.RtkFixed,
            satellitesVisible = 10,
            hdop = 1.21,
            vdop = 2.0,
            groundSpeed = 0.0,
            courseOverGround = null,
            horizontalAccuracy = 0.3,
            verticalAccuracy = 0.3,
            yaw = null,
        )
    }

    @Test
    fun `GIVEN a GPS_RAW_INT with a fix type GPS_FIX_TYPE does not define WHEN mapping it THEN keeps its number`() {
        val received = received(gpsRawInt(fixType = UNDEFINED_FIX_TYPE))

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result?.gps?.value?.fixType shouldBeEqualTo GpsFixType.Unknown(UNDEFINED_FIX_TYPE)
    }

    @Test
    @Tag("REQ-033")
    fun `GIVEN a real HOME_POSITION WHEN mapping it THEN is the home in degrees and metres`() {
        val received = receivedFixture(HOME_POSITION)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result shouldBeEqualTo Telemetry(home = Timed(REAL_HOME, RECEIVED_AT), origin = DataOrigin.LIVE)
    }

    @Test
    @Tag("REQ-033")
    fun `GIVEN a known home WHEN a HOME_POSITION at another point arrives THEN replaces it`() {
        val telemetry = Telemetry(home = Timed(REAL_HOME, EARLIER), origin = DataOrigin.LIVE)

        val result = mapper.update(telemetry, receivedFixture(HOME_POSITION_MOVED), RECEIVED_AT)

        result?.home shouldBeEqualTo Timed(
            Home(latitude = -35.3624514, longitude = 149.1663411, altitudeMsl = 590.13),
            RECEIVED_AT,
        )
    }

    @Test
    @Tag("REQ-031")
    @Tag("REQ-033")
    fun `GIVEN a known home WHEN a HOME_POSITION places it nowhere or at 0, 0 THEN the frame is dropped`() {
        val telemetry = Telemetry(home = Timed(REAL_HOME, EARLIER), origin = DataOrigin.LIVE)
        val noHome = listOf(HOME_POSITION_LAT_PAST_90, HOME_POSITION_LAT_INT32_MIN, HOME_POSITION_NULL_ISLAND)

        val result = noHome.map { path -> mapper.update(telemetry, receivedFixture(path), RECEIVED_AT) }

        result shouldBeEqualTo listOf(null, null, null)
    }

    @Test
    @Tag("REQ-033")
    fun `GIVEN a HOME_POSITION from another component of the vehicle WHEN mapping it THEN is not the vehicle's`() {
        val received = receivedFixture(HOME_POSITION).copy(componentId = MAV_COMP_ID_GIMBAL)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN telemetry with other parts WHEN mapping a message THEN only its own part changes`() {
        val telemetry = Telemetry(battery = Timed(UNHEALTHY_WITHOUT_READING, EARLIER), origin = DataOrigin.LIVE)

        val result = mapper.update(telemetry, receivedFixture(ATTITUDE), RECEIVED_AT)

        result?.battery shouldBeEqualTo Timed(UNHEALTHY_WITHOUT_READING, EARLIER)
    }

    @Test
    fun `GIVEN telemetry from another component of the vehicle WHEN mapping it THEN is not the vehicle's`() {
        val received = receivedFixture(ATTITUDE).copy(componentId = MAV_COMP_ID_GIMBAL)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a message that is not telemetry WHEN mapping it THEN is not telemetry`() {
        val received = receivedFixture(HEARTBEAT)

        val result = mapper.update(Telemetry(origin = DataOrigin.LIVE), received, RECEIVED_AT)

        result.shouldBeNull()
    }

    private fun receivedFixture(path: String): ReceivedMessage {
        val frame = MavlinkParser(MavlinkMessages).parse(readFixture(path)).single()
        return ReceivedMessage(
            frame.systemId,
            frame.componentId,
            MavlinkDecoder.decode(frame),
            SENDER,
            session = FIRST_SESSION,
        )
    }
}

private const val GLOBAL_POSITION_INT = "telemetry/global-position-int.bin"
private const val GLOBAL_POSITION_INT_EDGES = "derived/global-position-int-edges.bin"
private const val GLOBAL_POSITION_INT_HDG_36000 = "derived/global-position-int-hdg-36000.bin"
private const val GLOBAL_POSITION_INT_LAT_PAST_90 = "derived/global-position-int-lat-past-90.bin"
private const val GLOBAL_POSITION_INT_LON_PAST_180 = "derived/global-position-int-lon-past-180.bin"
private const val GLOBAL_POSITION_INT_LON_PAST_180_EAST = "derived/global-position-int-lon-past-180-east.bin"
private const val GLOBAL_POSITION_INT_ENDS_SOUTH_EAST = "derived/global-position-int-ends-south-east.bin"
private const val GLOBAL_POSITION_INT_LAT_INT32_MIN = "derived/global-position-int-lat-int32-min.bin"
private const val GLOBAL_POSITION_INT_ENDS = "derived/global-position-int-ends.bin"
private const val ATTITUDE_OUT_OF_RANGE = "derived/attitude-out-of-range.bin"
private const val ATTITUDE_PAST_PI = "derived/attitude-past-pi.bin"
private const val VFR_HUD_OUT_OF_RANGE = "derived/vfr-hud-out-of-range.bin"
private const val VFR_HUD_THROTTLE_100 = "derived/vfr-hud-throttle-100.bin"
private const val GPS_RAW_INT_OUT_OF_RANGE = "derived/gps-raw-int-out-of-range.bin"
private const val ATTITUDE = "telemetry/attitude.bin"
private const val ATTITUDE_EDGES = "derived/attitude-edges.bin"
private const val VFR_HUD = "telemetry/vfr-hud.bin"
private const val VFR_HUD_EDGES = "derived/vfr-hud-edges.bin"
private const val SYS_STATUS = "telemetry/sys-status.bin"
private const val SYS_STATUS_EDGES = "derived/sys-status-edges.bin"
private const val SYS_STATUS_UNKNOWN_BATTERY = "derived/sys-status-unknown-battery.bin"
private const val SYS_STATUS_BOOT = "truncated/sys_status.bin"
private const val SYS_STATUS_REMAINING_MIN = "derived/sys-status-battery-remaining-min.bin"
private const val SYS_STATUS_ZERO_VOLTS = "derived/sys-status-battery-zero-volts.bin"
private const val SYS_STATUS_NO_MONITOR = "derived/sys-status-no-battery-monitor.bin"
private const val SYS_STATUS_UNHEALTHY = "derived/sys-status-battery-unhealthy.bin"
private const val GPS_RAW_INT = "telemetry/gps-raw-int.bin"
private const val GPS_RAW_INT_EDGES = "derived/gps-raw-int-edges.bin"
private const val GPS_RAW_INT_UNKNOWN = "derived/gps-raw-int-unknown.bin"
private const val HOME_POSITION = "telemetry/home-position.bin"
private const val HOME_POSITION_MOVED = "derived/home-position-moved.bin"
private const val HOME_POSITION_LAT_PAST_90 = "derived/home-position-lat-past-90.bin"
private const val HOME_POSITION_LAT_INT32_MIN = "derived/home-position-lat-int32-min.bin"
private const val HOME_POSITION_NULL_ISLAND = "derived/home-position-null-island.bin"
private const val HEARTBEAT = "heartbeat/disarmed-stabilize.bin"

private const val SYSTEM_ID = 1
private const val MAV_COMP_ID_AUTOPILOT1 = 1
private const val MAV_COMP_ID_GIMBAL = 154

/** `GPS_FIX_TYPE` ends at 8 (`GPS_FIX_TYPE_PPP`) in `common.xml`. */
private const val UNDEFINED_FIX_TYPE = 9

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_551)

private val CLOCK = TestTimeSource()
private val EARLIER = CLOCK.markNow()
private val RECEIVED_AT = CLOCK.markNow()

/** `telemetry/global-position-int.bin` as pymavlink decodes it, converted by hand. */
private val REAL_POSITION = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = -0.013,
    velocityNorth = 0.01,
    velocityEast = -0.01,
    velocityDown = 0.0,
    heading = 359.35,
)

/** `telemetry/home-position.bin` as pymavlink decodes it, converted by hand. */
private val REAL_HOME = Home(latitude = -35.3633514, longitude = 149.1652411, altitudeMsl = 587.13)

private val FULL_BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

/** The battery bit enabled but not healthy, as at boot before the monitor has a reading. */
private val UNHEALTHY_WITHOUT_READING = Battery(
    voltage = null,
    current = null,
    remaining = null,
    monitored = true,
    healthy = false,
)

private fun received(message: MavlinkMessage) =
    ReceivedMessage(SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, message, SENDER, session = FIRST_SESSION)

private fun gpsRawInt(fixType: Int = 3, yaw: Int = 0) = MavlinkMessage.GpsRawInt(
    timeUsec = 396_763_000,
    fixType = fixType,
    lat = -353_633_515,
    lon = 1_491_652_412,
    alt = 587_150,
    eph = 121,
    epv = 200,
    vel = 0,
    cog = 0,
    satellitesVisible = 10,
    altEllipsoid = 587_150,
    hAcc = 300,
    vAcc = 300,
    velAcc = 0,
    hdgAcc = 0,
    yaw = yaw,
)
