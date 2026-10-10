package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Enum values from `minimal.xml` (https://github.com/mavlink/mavlink), written out here, not taken from the mapper. */
class HeartbeatMapperTest {

    private lateinit var mapper: HeartbeatMapper

    @BeforeEach
    fun setUp() {
        mapper = HeartbeatMapper
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN an armed ArduCopter HEARTBEAT WHEN mapping it THEN the vehicle is armed in its named mode`() {
        val received = received(heartbeat(baseMode = ARMED_GUIDED_BASE_MODE, customMode = GUIDED))

        val result = mapper.vehicleStateOf(received)

        result shouldBeEqualTo copter(FlightMode.Named("Guided"), armed = true)
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN a disarmed ArduCopter HEARTBEAT WHEN mapping it THEN the vehicle is disarmed in its named mode`() {
        val received = received(heartbeat(baseMode = DISARMED_GUIDED_BASE_MODE, customMode = GUIDED))

        val result = mapper.vehicleStateOf(received)

        result shouldBeEqualTo copter(FlightMode.Named("Guided"), armed = false)
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN an ArduPilot HEARTBEAT from a non-copter type WHEN mapping it THEN its mode is Unknown`() {
        val received = received(heartbeat(type = MAV_TYPE_FIXED_WING, customMode = GUIDED))

        val result = mapper.vehicleStateOf(received)

        result shouldBeEqualTo VehicleState(
            systemId = SystemId(SYSTEM_ID),
            type = VehicleType.OTHER,
            flightMode = FlightMode.Unknown(GUIDED),
            armed = false,
        )
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN a copter HEARTBEAT from another autopilot WHEN mapping it THEN its mode is Unknown`() {
        val received = received(heartbeat(autopilot = MAV_AUTOPILOT_PX4, customMode = GUIDED))

        val result = mapper.vehicleStateOf(received)

        result shouldBeEqualTo copter(FlightMode.Unknown(GUIDED), armed = false)
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a HEARTBEAT from a ground station WHEN mapping it THEN is not a vehicle`() {
        val received = received(heartbeat(type = MAV_TYPE_GCS, autopilot = MAV_AUTOPILOT_INVALID))

        val result = mapper.vehicleStateOf(received)

        result.shouldBeNull()
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a HEARTBEAT from another component of the vehicle WHEN mapping it THEN is not the vehicle`() {
        val received = received(heartbeat(type = MAV_TYPE_GIMBAL), componentId = MAV_COMP_ID_GIMBAL)

        val result = mapper.vehicleStateOf(received)

        result.shouldBeNull()
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a HEARTBEAT with an invalid autopilot WHEN mapping it THEN is not a vehicle`() {
        val received = received(heartbeat(autopilot = MAV_AUTOPILOT_INVALID))

        val result = mapper.vehicleStateOf(received)

        result.shouldBeNull()
    }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a message that is not a HEARTBEAT WHEN mapping it THEN is not a vehicle state`() {
        val received = received(MavlinkMessage.Undecoded(messageId = SYS_STATUS_ID, payload = ByteArray(0)))

        val result = mapper.vehicleStateOf(received)

        result.shouldBeNull()
    }
}

private const val SYSTEM_ID = 1
private const val MAV_COMP_ID_AUTOPILOT1 = 1
private const val MAV_COMP_ID_GIMBAL = 154
private const val MAV_TYPE_FIXED_WING = 1
private const val MAV_TYPE_QUADROTOR = 2
private const val MAV_TYPE_GCS = 6
private const val MAV_TYPE_GIMBAL = 26
private const val MAV_AUTOPILOT_ARDUPILOTMEGA = 3
private const val MAV_AUTOPILOT_INVALID = 8
private const val MAV_AUTOPILOT_PX4 = 12
private const val MAV_STATE_STANDBY = 3
private const val MAVLINK_VERSION = 3
private const val SYS_STATUS_ID = 1
private const val GUIDED = 4L

// base_mode of the real SITL captures (core/mavlink/src/test/resources/README.md): 217 adds SAFETY_ARMED (128).
private const val DISARMED_GUIDED_BASE_MODE = 89
private const val ARMED_GUIDED_BASE_MODE = 217

private fun received(message: MavlinkMessage, componentId: Int = MAV_COMP_ID_AUTOPILOT1) =
    ReceivedMessage(systemId = SYSTEM_ID, componentId = componentId, message = message)

private fun heartbeat(
    type: Int = MAV_TYPE_QUADROTOR,
    autopilot: Int = MAV_AUTOPILOT_ARDUPILOTMEGA,
    baseMode: Int = DISARMED_GUIDED_BASE_MODE,
    customMode: Long = GUIDED,
) = MavlinkMessage.Heartbeat(
    type = type,
    autopilot = autopilot,
    baseMode = baseMode,
    customMode = customMode,
    systemStatus = MAV_STATE_STANDBY,
    mavlinkVersion = MAVLINK_VERSION,
)

private fun copter(flightMode: FlightMode, armed: Boolean) = VehicleState(
    systemId = SystemId(SYSTEM_ID),
    type = VehicleType.COPTER,
    flightMode = flightMode,
    armed = armed,
)
