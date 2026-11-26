package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestList
import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Mission transfer encoder tests: each frame is byte-identical to what QGroundControl 5.0.8 sent to ArduCopter
 * 4.8.0-dev SITL through the relay (sequence, system 255 and component 190 as captured), or to a frame pymavlink
 * re-packed from one of them (see `src/test/resources/README.md`, "Mission transfer").
 */
@Tag("REQ-053")
class MissionEncoderTest {

    private lateinit var encoder: MavlinkEncoder

    @BeforeEach
    fun setUp() {
        encoder = MavlinkEncoder
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "mission/count-upload.bin, 89, 5, 0",
        "mission/count-fence-empty.bin, 95, 0, 1",
    )
    fun `GIVEN QGroundControl's MISSION_COUNT WHEN encoding it THEN the bytes are the captured frame`(
        fixture: String,
        sequence: Int,
        count: Int,
        missionType: Int,
    ) {
        val message = MissionCount(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, count, missionType, opaqueId = 0)

        val result = encoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture(fixture)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "mission/request-list.bin, 133, 0",
        "mission/request-list-fence.bin, 140, 1",
    )
    fun `GIVEN QGroundControl's MISSION_REQUEST_LIST WHEN encoding it THEN the bytes are the captured frame`(
        fixture: String,
        sequence: Int,
        missionType: Int,
    ) {
        val message = MissionRequestList(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, missionType)

        val result = encoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture(fixture)
    }

    @Test
    fun `GIVEN QGroundControl's MISSION_REQUEST_INT WHEN encoding it THEN the bytes are the captured frame`() {
        val message = MissionRequestInt(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, seq = 0, missionType = 0)

        val result = encoder.encode(REQUEST_INT_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("mission/request-int-seq-0.bin")
    }

    @Test
    fun `GIVEN QGroundControl's MISSION_ACK WHEN encoding it THEN the bytes are the captured frame`() {
        val message = MissionAck(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, type = 0, missionType = 0, opaqueId = 0)

        val result = encoder.encode(ACK_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("mission/ack-download.bin")
    }

    @Test
    fun `GIVEN QGroundControl's home item WHEN encoding it THEN the bytes are the captured frame`() {
        val message = item(seq = 0, frame = MAV_FRAME_GLOBAL, command = MAV_CMD_NAV_WAYPOINT, z = HOME_ALTITUDE_AMSL)
            .copy(current = 1, param4 = 0f)

        val result = encoder.encode(HOME_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("mission/item-home.bin")
    }

    @Test
    fun `GIVEN QGroundControl's take off item with a NaN yaw WHEN encoding it THEN the bytes are the captured frame`() {
        val message = item(seq = 1, frame = MAV_FRAME_GLOBAL_RELATIVE_ALT, command = MAV_CMD_NAV_TAKEOFF, z = ALTITUDE)

        val result = encoder.encode(TAKEOFF_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("mission/item-takeoff.bin")
    }

    @Test
    fun `GIVEN QGroundControl's waypoint with a hold WHEN encoding it THEN the bytes are the captured frame`() {
        val message = item(seq = 2, frame = MAV_FRAME_GLOBAL_RELATIVE_ALT, command = MAV_CMD_NAV_WAYPOINT, z = ALTITUDE)
            .copy(param1 = HOLD_SECONDS, x = WAYPOINT_LATITUDE, y = WAYPOINT_LONGITUDE)

        val result = encoder.encode(WAYPOINT_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("mission/item-waypoint-hold.bin")
    }

    @Test
    fun `GIVEN a return to launch item WHEN encoding it THEN the bytes match the pymavlink frame`() {
        val message = item(seq = 5, frame = MAV_FRAME_GLOBAL, command = MAV_CMD_NAV_RETURN_TO_LAUNCH, z = 0f)
            .copy(param4 = 0f, x = 0, y = 0)

        val result = encoder.encode(DERIVED_ITEM_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-item-rtl.bin")
    }

    @Test
    fun `GIVEN an item with every field distinct WHEN encoding it THEN the bytes match the pymavlink frame`() {
        val message = MissionItemInt(
            targetSystem = OTHER_SYSTEM_ID,
            targetComponent = OTHER_COMPONENT_ID,
            seq = 0x0102,
            frame = UNKNOWN_FRAME,
            command = 0x0304,
            current = 1,
            autocontinue = 0,
            param1 = 1.5f,
            param2 = -2.25f,
            param3 = 100_000f,
            param4 = -1f,
            x = HOME_LATITUDE,
            y = HOME_LONGITUDE,
            z = -0.5f,
            missionType = 2,
        )

        val result = encoder.encode(DERIVED_ITEM_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-item-full.bin")
    }

    @Test
    fun `GIVEN a MISSION_COUNT with every field distinct WHEN encoding it THEN the bytes match the pymavlink frame`() {
        val message = MissionCount(OTHER_SYSTEM_ID, OTHER_COMPONENT_ID, 0x0102, MAV_MISSION_TYPE_FENCE, OPAQUE_ID)

        val result = encoder.encode(COUNT_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-count-fields.bin")
    }

    @Test
    fun `GIVEN a MISSION_ACK with every field distinct WHEN encoding it THEN the bytes match the pymavlink frame`() {
        val message = MissionAck(
            OTHER_SYSTEM_ID,
            OTHER_COMPONENT_ID,
            MAV_MISSION_INVALID_SEQUENCE,
            MAV_MISSION_TYPE_FENCE,
            OPAQUE_ID,
        )

        val result = encoder.encode(ACK_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-ack-fields.bin")
    }

    @Test
    fun `GIVEN a MISSION_REQUEST_INT with every field distinct WHEN encoding it THEN the bytes match pymavlink's`() {
        val message = MissionRequestInt(OTHER_SYSTEM_ID, OTHER_COMPONENT_ID, 0x0102, MAV_MISSION_TYPE_FENCE)

        val result = encoder.encode(REQUEST_INT_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-request-int-fields.bin")
    }

    @Test
    fun `GIVEN a MISSION_REQUEST_LIST with distinct targets WHEN encoding it THEN the bytes match pymavlink's`() {
        val message = MissionRequestList(OTHER_SYSTEM_ID, OTHER_COMPONENT_ID, MAV_MISSION_TYPE_RALLY)

        val result = encoder.encode(REQUEST_LIST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        result shouldBeEqualTo readFixture("derived/mission-request-list-fields.bin")
    }

    @ParameterizedTest(name = "count {0}, mission_type {1}, opaque_id {2}")
    @CsvSource(
        "-1, 0, 0",
        "65536, 0, 0",
        "0, 256, 0",
        "0, 0, -1",
        "0, 0, 4294967296",
    )
    fun `GIVEN a MISSION_COUNT field outside its type WHEN encoding THEN it throws`(
        count: Int,
        missionType: Int,
        opaqueId: Long,
    ) {
        val message = MissionCount(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, count, missionType, opaqueId)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message) }

        result shouldThrow IllegalArgumentException::class
    }

    @ParameterizedTest(name = "type {0}, mission_type {1}")
    @CsvSource(
        "256, 0",
        "0, 256",
    )
    fun `GIVEN a MISSION_ACK field outside one byte WHEN encoding THEN it throws`(type: Int, missionType: Int) {
        val message = MissionAck(VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, type, missionType, opaqueId = 0)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message) }

        result shouldThrow IllegalArgumentException::class
    }

    @ParameterizedTest(name = "seq {0}, command {1}, frame {2}")
    @CsvSource(
        "-1, 16, 3",
        "65536, 16, 3",
        "0, 65536, 3",
        "0, 16, 256",
    )
    fun `GIVEN a MISSION_ITEM_INT field outside its type WHEN encoding THEN it throws`(
        seq: Int,
        command: Int,
        frame: Int,
    ) {
        val message = item(seq = seq, frame = frame, command = command, z = ALTITUDE)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message) }

        result shouldThrow IllegalArgumentException::class
    }

    @ParameterizedTest(name = "target {0}/{1}")
    @CsvSource(
        "256, 1",
        "1, -1",
    )
    fun `GIVEN a target outside one byte WHEN encoding a MISSION_REQUEST_INT THEN it throws`(
        targetSystem: Int,
        targetComponent: Int,
    ) {
        val message = MissionRequestInt(targetSystem, targetComponent, seq = 0, missionType = 0)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message) }

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN an encoded item WHEN parsing and decoding it THEN it is the same item`() {
        val message = item(seq = 2, frame = MAV_FRAME_GLOBAL_RELATIVE_ALT, command = MAV_CMD_NAV_WAYPOINT, z = ALTITUDE)
        val frame = encoder.encode(WAYPOINT_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)

        val result = MavlinkDecoder.decode(MavlinkParser(MavlinkMessages).parse(frame).single())

        result shouldBeEqualTo message
    }

    private companion object {
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val VEHICLE_SYSTEM_ID = 1
        const val VEHICLE_COMPONENT_ID = 1

        // pymavlink-derived frames: a target whose system and component differ, every field distinct.
        const val OTHER_SYSTEM_ID = 2
        const val OTHER_COMPONENT_ID = 3
        const val OPAQUE_ID = 0x8765_4321L
        const val UNKNOWN_FRAME = 255

        // The derived items keep the take off's sequence number, 91.
        const val DERIVED_ITEM_SEQUENCE = 91
        const val MAV_MISSION_TYPE_FENCE = 1
        const val MAV_MISSION_TYPE_RALLY = 2
        const val MAV_MISSION_INVALID_SEQUENCE = 13
        const val COUNT_SEQUENCE = 89
        const val REQUEST_LIST_SEQUENCE = 133

        // QGroundControl's frames in mission/session.tlog, as pymavlink decodes them.
        const val HOME_SEQUENCE = 90
        const val TAKEOFF_SEQUENCE = 91
        const val WAYPOINT_SEQUENCE = 92
        const val REQUEST_INT_SEQUENCE = 134
        const val ACK_SEQUENCE = 139
        const val MAV_FRAME_GLOBAL = 0
        const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        const val MAV_CMD_NAV_WAYPOINT = 16
        const val MAV_CMD_NAV_RETURN_TO_LAUNCH = 20
        const val MAV_CMD_NAV_TAKEOFF = 22
        const val HOME_LATITUDE = -353633515
        const val HOME_LONGITUDE = 1491652412
        const val HOME_ALTITUDE_AMSL = 585f
        const val WAYPOINT_LATITUDE = -353623715
        const val WAYPOINT_LONGITUDE = 1491639215
        const val ALTITUDE = 10f
        const val HOLD_SECONDS = 5f

        /** QGroundControl's item shape: autocontinue 1, yaw NaN ("unchanged"), at home's position. */
        fun item(seq: Int, frame: Int, command: Int, z: Float) = MissionItemInt(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = VEHICLE_COMPONENT_ID,
            seq = seq,
            frame = frame,
            command = command,
            current = 0,
            autocontinue = 1,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = Float.NaN,
            x = HOME_LATITUDE,
            y = HOME_LONGITUDE,
            z = z,
            missionType = 0,
        )
    }
}
