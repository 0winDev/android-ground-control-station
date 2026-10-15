package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
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
            SystemId(1) to Telemetry(attitude = Timed(LEVEL, FIRST)),
            SystemId(2) to Telemetry(battery = Timed(FULL_BATTERY, SECOND)),
        )
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN a newer message of the same kind WHEN recording it THEN replaces the value and its time`() {
        store.record(received(systemId = 1, message = attitude(roll = 0f)), FIRST)

        store.record(received(systemId = 1, message = attitude(roll = HALF_PI)), SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo mapOf(SystemId(1) to Telemetry(attitude = Timed(ROLLED_90, SECOND)))
    }

    @Tag("REQ-031")
    @Test
    fun `GIVEN a known position WHEN a position that does not exist arrives THEN the last one keeps its time`() {
        store.record(received(systemId = 1, message = globalPositionInt(lat = SOUTH_LAT_DEGE7)), FIRST)

        store.record(received(systemId = 1, message = globalPositionInt(lat = PAST_NORTH_POLE_DEGE7)), SECOND)
        val result = store.telemetry.value

        result shouldBeEqualTo mapOf(SystemId(1) to Telemetry(position = Timed(SOUTH_POSITION, FIRST)))
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
}

private const val HALF_PI = (Math.PI / 2).toFloat()
private const val MAV_COMP_ID_AUTOPILOT1 = 1

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
private val CLOCK = TestTimeSource()
private val FIRST = CLOCK.markNow()
private val SECOND = CLOCK.markNow()

private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

// The float closest to π/2, in degrees as Python's math.degrees gives it.
private val ROLLED_90 = Attitude(roll = 90.00000250447816, pitch = 0.0, yaw = 0.0)
private val FULL_BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

private fun received(systemId: Int, message: MavlinkMessage) =
    ReceivedMessage(systemId, MAV_COMP_ID_AUTOPILOT1, message, SENDER)

private fun attitude(roll: Float) = MavlinkMessage.Attitude(
    timeBootMs = 397_119,
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

private fun globalPositionInt(lat: Int) = MavlinkMessage.GlobalPositionInt(
    timeBootMs = 397_289,
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
