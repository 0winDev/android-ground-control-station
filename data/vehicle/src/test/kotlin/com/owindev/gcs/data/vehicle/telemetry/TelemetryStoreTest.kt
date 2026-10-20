package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Mapping itself is covered with real frames in [TelemetryMapperTest]; here, only where each value is kept. */
class TelemetryStoreTest {

    private lateinit var store: TelemetryStore

    @BeforeEach
    fun setUp() {
        store = TelemetryStore()
    }

    @Tag("REQ-016")
    @Tag("REQ-026")
    @Test
    fun `GIVEN telemetry from two systems WHEN recording it THEN each system keeps its own`() {
        val fromSystem1 = received(systemId = 1, message = attitude(roll = 0f))
        val fromSystem2 = received(systemId = 2, message = sysStatus())

        store.record(fromSystem1, FIRST)
        store.record(fromSystem2, SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo mapOf(
            SystemId(1) to Telemetry(attitude = Timed(LEVEL, FIRST), origin = DataOrigin.LIVE),
            SystemId(2) to Telemetry(battery = Timed(FULL_BATTERY, SECOND), origin = DataOrigin.LIVE),
        )
    }

    @Tag("REQ-026")
    @Tag("REQ-037")
    @Test
    fun `GIVEN live telemetry WHEN a replay session starts THEN it is forgotten and only the replay's is kept`() {
        store.record(received(systemId = 1, message = attitude(roll = 0f)), FIRST)

        store.startSession(DataOrigin.REPLAY)
        store.record(received(systemId = 2, message = attitude(roll = 0f)), SECOND)
        store.record(received(systemId = 2, message = sysStatus()).copy(origin = DataOrigin.REPLAY), SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo mapOf(
            SystemId(2) to Telemetry(battery = Timed(FULL_BATTERY, SECOND), origin = DataOrigin.REPLAY),
        )
    }

    @Test
    fun `GIVEN a newer message of the same kind WHEN recording it THEN replaces the value and its time`() {
        store.record(received(systemId = 1, message = attitude(roll = 0f)), FIRST)

        store.record(received(systemId = 1, message = attitude(roll = HALF_PI)), SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo
            mapOf(SystemId(1) to Telemetry(attitude = Timed(ROLLED_90, SECOND), origin = DataOrigin.LIVE))
    }

    @Tag("REQ-031")
    @Test
    fun `GIVEN a known position WHEN a position that does not exist arrives THEN the last one keeps its time`() {
        store.record(received(systemId = 1, message = globalPositionInt(lat = SOUTH_LAT_DEGE7)), FIRST)

        store.record(received(systemId = 1, message = globalPositionInt(lat = PAST_NORTH_POLE_DEGE7)), SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo
            mapOf(SystemId(1) to Telemetry(position = Timed(SOUTH_POSITION, FIRST), origin = DataOrigin.LIVE))
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN telemetry with the broadcast system ID WHEN recording it THEN is not kept`() {
        val broadcast = received(systemId = 0, message = attitude(roll = 0f))

        store.record(broadcast, FIRST)
        val result = store.telemetry.value

        result shouldBeEqualTo emptyMap()
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a message that is not telemetry WHEN recording it THEN nothing is kept`() {
        val heartbeat = received(
            systemId = 1,
            message = MavlinkMessage.Heartbeat(
                type = 2,
                autopilot = 3,
                baseMode = 81,
                customMode = 0,
                systemStatus = 3,
                mavlinkVersion = 3,
            ),
        )

        store.record(heartbeat, FIRST)
        val result = store.telemetry.value

        result shouldBeEqualTo emptyMap()
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN an ATTITUDE goes back in time past the reboot backstep THEN home is forgotten`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)

        val rebooted = store.record(
            received(systemId = 1, message = attitude(roll = 0f, timeBootMs = JUST_BOOTED)),
            SECOND,
        )
        val result = store.telemetry.value

        rebooted.shouldBeTrue()
        result shouldBeEqualTo
            mapOf(SystemId(1) to Telemetry(attitude = Timed(LEVEL, SECOND), origin = DataOrigin.LIVE))
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN a GLOBAL_POSITION_INT goes back in time past the reboot backstep THEN is a reboot`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 1, message = globalPositionInt(SOUTH_LAT_DEGE7, UP_FOR_A_MINUTE)), FIRST)

        val rebooted = store.record(
            received(systemId = 1, message = globalPositionInt(SOUTH_LAT_DEGE7, JUST_BOOTED)),
            SECOND,
        )
        val result = store.telemetry.value[SystemId(1)]?.home

        rebooted.shouldBeTrue()
        result.shouldBeNull()
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN an ATTITUDE arrives out of order within the reboot backstep THEN home is kept`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)

        val rebooted = store.record(
            received(systemId = 1, message = attitude(roll = 0f, timeBootMs = AT_THE_BACKSTEP)),
            SECOND,
        )
        val result = store.telemetry.value[SystemId(1)]?.home

        rebooted.shouldBeFalse()
        result shouldBeEqualTo Timed(HOME, FIRST)
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN ATTITUDE and GLOBAL_POSITION_INT stamped apart WHEN each moves forward THEN is no reboot`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)

        val rebooted = store.record(
            received(systemId = 1, message = globalPositionInt(SOUTH_LAT_DEGE7, JUST_BOOTED)),
            SECOND,
        )
        val result = store.telemetry.value[SystemId(1)]?.home

        rebooted.shouldBeFalse()
        result shouldBeEqualTo Timed(HOME, FIRST)
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a reboot seen on ATTITUDE WHEN GLOBAL_POSITION_INT goes back in time too THEN is the same reboot`() {
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)
        store.record(received(systemId = 1, message = globalPositionInt(SOUTH_LAT_DEGE7, UP_FOR_A_MINUTE)), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = JUST_BOOTED)), SECOND)

        val rebooted = store.record(
            received(systemId = 1, message = globalPositionInt(SOUTH_LAT_DEGE7, JUST_BOOTED)),
            SECOND,
        )

        rebooted.shouldBeFalse()
    }

    @Tag("REQ-016")
    @Tag("REQ-033")
    @Test
    fun `GIVEN homes of two systems WHEN one reboots THEN only its home is forgotten`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 2, message = home()), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)
        store.record(received(systemId = 2, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)

        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = JUST_BOOTED)), SECOND)
        val result = store.telemetry.value.mapValues { (_, telemetry) -> telemetry.home }

        result shouldBeEqualTo mapOf(SystemId(1) to null, SystemId(2) to Timed(HOME, FIRST))
    }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN another component's ATTITUDE goes back in time THEN is no reboot`() {
        store.record(received(systemId = 1, message = home()), FIRST)
        store.record(received(systemId = 1, message = attitude(roll = 0f, timeBootMs = UP_FOR_A_MINUTE)), FIRST)
        val gimbal = received(systemId = 1, message = attitude(roll = 0f, timeBootMs = JUST_BOOTED))
            .copy(componentId = MAV_COMP_ID_GIMBAL)

        val rebooted = store.record(gimbal, SECOND)
        val result = store.telemetry.value[SystemId(1)]?.home

        rebooted.shouldBeFalse()
        result shouldBeEqualTo Timed(HOME, FIRST)
    }
}

private const val HALF_PI = (Math.PI / 2).toFloat()
private const val MAV_COMP_ID_AUTOPILOT1 = 1
private const val MAV_COMP_ID_GIMBAL = 154

/** `time_boot_ms`: a minute up, back to 1 s (a reboot), or back by exactly [TelemetryStore.REBOOT_BACKSTEP]. */
private const val UP_FOR_A_MINUTE = 60_000L
private const val JUST_BOOTED = 1_000L
private val AT_THE_BACKSTEP = UP_FOR_A_MINUTE - TelemetryStore.REBOOT_BACKSTEP.inWholeMilliseconds

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
private val CLOCK = TestTimeSource()
private val FIRST = CLOCK.markNow()
private val SECOND = CLOCK.markNow()

private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

// The float closest to π/2, in degrees as Python's math.degrees gives it.
private val ROLLED_90 = Attitude(roll = 90.00000250447816, pitch = 0.0, yaw = 0.0)
private val FULL_BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

private fun received(systemId: Int, message: MavlinkMessage) =
    ReceivedMessage(systemId, MAV_COMP_ID_AUTOPILOT1, message, SENDER, origin = DataOrigin.LIVE)

private fun attitude(roll: Float, timeBootMs: Long = 397_119) = MavlinkMessage.Attitude(
    timeBootMs = timeBootMs,
    roll = roll,
    pitch = 0f,
    yaw = 0f,
    rollspeed = 0f,
    pitchspeed = 0f,
    yawspeed = 0f,
)

/** 35° S, and 90.0000001° N, past the pole: no place on the WGS84 ellipsoid. */
private const val SOUTH_LAT_DEGE7 = -350_000_000
private const val PAST_NORTH_POLE_DEGE7 = 900_000_001

private val SOUTH_POSITION = GlobalPosition(
    latitude = -35.0,
    longitude = 149.0,
    altitudeMsl = 0.0,
    altitudeAboveHome = 0.0,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = 0.0,
)

private fun globalPositionInt(lat: Int, timeBootMs: Long = 397_289) = MavlinkMessage.GlobalPositionInt(
    timeBootMs = timeBootMs,
    lat = lat,
    lon = 1_490_000_000,
    alt = 0,
    relativeAlt = 0,
    vx = 0,
    vy = 0,
    vz = 0,
    hdg = 0,
)

/** `common.xml`: a battery monitor enabled and healthy. */
private const val MAV_SYS_STATUS_SENSOR_BATTERY = 0x0200_0000L

private fun sysStatus() = MavlinkMessage.SysStatus(
    onboardControlSensorsPresent = 0,
    onboardControlSensorsEnabled = MAV_SYS_STATUS_SENSOR_BATTERY,
    onboardControlSensorsHealth = MAV_SYS_STATUS_SENSOR_BATTERY,
    load = 0,
    voltageBattery = 12_600,
    currentBattery = 0,
    batteryRemaining = 100,
    dropRateComm = 0,
    errorsComm = 0,
    errorsCount1 = 0,
    errorsCount2 = 0,
    errorsCount3 = 0,
    errorsCount4 = 0,
    onboardControlSensorsPresentExtended = 0,
    onboardControlSensorsEnabledExtended = 0,
    onboardControlSensorsHealthExtended = 0,
)

private val HOME = Home(latitude = -35.0, longitude = 149.0, altitudeMsl = 584.0)

private fun home() = MavlinkMessage.HomePosition(
    latitude = -350_000_000,
    longitude = 1_490_000_000,
    altitude = 584_000,
    x = 0f,
    y = 0f,
    z = 0f,
    q1 = Float.NaN,
    q2 = Float.NaN,
    q3 = Float.NaN,
    q4 = Float.NaN,
    approachX = 0f,
    approachY = 0f,
    approachZ = 0f,
    timeUsec = 443_979_837,
)
