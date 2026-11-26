package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequest
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionRequestInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Mission transfer decoder tests from a QGroundControl 5.0.8 upload and download through the SITL relay (ArduCopter
 * 4.8.0-dev) and frames re-packed by pymavlink, run through the parser with the codec's message table. Expected values
 * come from pymavlink (see `src/test/resources/README.md`, "Mission transfer").
 */
@Tag("REQ-053")
class MavlinkDecoderMissionTest {

    private lateinit var decoder: MavlinkDecoder

    @BeforeEach
    fun setUp() {
        decoder = MavlinkDecoder
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "mission/request-seq-0.bin, 0, 0",
        "mission/request-seq-4.bin, 4, 0",
        "derived/mission-request-seq-high.bin, 65534, 0",
        "derived/mission-request-rally.bin, 4, 2",
    )
    fun `GIVEN a MISSION_REQUEST from the vehicle WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        seq: Int,
        missionType: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo MissionRequest(GCS_SYSTEM_ID, GCS_COMPONENT_ID, seq, missionType)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "mission/request-int-seq-0.bin, 1, 1, 0, 0",
        "derived/mission-request-int-seq-high.bin, 1, 1, 65534, 1",
        "derived/mission-request-int-fields.bin, 2, 3, 258, 1",
    )
    fun `GIVEN a MISSION_REQUEST_INT WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        targetSystem: Int,
        targetComponent: Int,
        seq: Int,
        missionType: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo MissionRequestInt(targetSystem, targetComponent, seq, missionType)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("counts")
    fun `GIVEN a MISSION_COUNT WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        expected: MissionCount,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo expected
    }

    @Tag("REQ-013")
    @ParameterizedTest(name = "{0}")
    @MethodSource("acks")
    fun `GIVEN a MISSION_ACK WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        expected: MissionAck,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo expected
    }

    @Test
    fun `GIVEN the vehicle's MISSION_ITEM_INT WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame("mission/item-download-waypoint.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo MissionItemInt(
            targetSystem = GCS_SYSTEM_ID,
            targetComponent = GCS_COMPONENT_ID,
            seq = 2,
            frame = MAV_FRAME_GLOBAL_RELATIVE_ALT,
            command = MAV_CMD_NAV_WAYPOINT,
            current = 0,
            autocontinue = 1,
            param1 = HOLD_SECONDS,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            x = WAYPOINT_LATITUDE,
            y = WAYPOINT_LONGITUDE,
            z = ALTITUDE,
            missionType = MAV_MISSION_TYPE_MISSION,
        )
    }

    @Test
    fun `GIVEN QGroundControl's home item WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame("mission/item-home.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo MissionItemInt(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = VEHICLE_COMPONENT_ID,
            seq = 0,
            frame = MAV_FRAME_GLOBAL,
            command = MAV_CMD_NAV_WAYPOINT,
            current = 1,
            autocontinue = 1,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            x = HOME_LATITUDE,
            y = HOME_LONGITUDE,
            z = HOME_ALTITUDE_AMSL,
            missionType = MAV_MISSION_TYPE_MISSION,
        )
    }

    @Test
    fun `GIVEN an item with every field distinct, an unknown frame and command WHEN decoding THEN each is kept`() {
        val frame = parsedFrame("derived/mission-item-full.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo fullItem()
    }

    @Test
    fun `GIVEN QGroundControl's MISSION_REQUEST_LIST WHEN decoding THEN it stays undecoded, the codec only sends it`() {
        val frame = parsedFrame("mission/request-list.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo Undecoded(MISSION_REQUEST_LIST_ID, byteArrayOf(1, 1, 0))
    }

    @Test
    fun `GIVEN the whole captured upload and download WHEN decoding THEN every message is the type pymavlink reads`() {
        val frames = TlogReader().read(readFixture("mission/session.tlog")).map { it.frame }
        val parser = MavlinkParser(MavlinkMessages)

        val result = frames.flatMap { parser.parse(it) }.map { decoder.decode(it).kind() }.groupingBy { it }.eachCount()

        result.toSortedMap() shouldBeEqualTo SESSION_COUNTS
    }

    private fun parsedFrame(fixture: String): MavlinkFrame =
        MavlinkParser(MavlinkMessages).parse(readFixture(fixture)).single()

    private fun MavlinkMessage.kind(): String = when (this) {
        is Undecoded -> "Undecoded $messageId"
        else -> this::class.simpleName.orEmpty()
    }

    private companion object {

        // As pymavlink decodes them (see the fixtures README).
        @JvmStatic
        fun counts(): List<Arguments> = listOf(
            arguments("mission/count-download.bin", MissionCount(255, 190, 5, 0, 0)),
            arguments("mission/count-fence-empty.bin", MissionCount(1, 1, 0, 1, 0)),
            arguments("derived/mission-count-opaque-id.bin", MissionCount(255, 190, 5, 0, OPAQUE_ID)),
            arguments("derived/mission-count-fields.bin", MissionCount(2, 3, 258, 1, OPAQUE_ID)),
        )

        @JvmStatic
        fun acks(): List<Arguments> = listOf(
            arguments("mission/ack-accepted.bin", MissionAck(255, 190, 0, 0, 0)),
            arguments("mission/ack-accepted-fence.bin", MissionAck(255, 190, 0, 1, 0)),
            arguments("derived/mission-ack-opaque-id.bin", MissionAck(255, 190, 0, 1, OPAQUE_ID)),
            arguments("derived/mission-ack-invalid-sequence.bin", MissionAck(255, 190, 13, 0, 0)),
            arguments("derived/mission-ack-type-past-enum.bin", MissionAck(255, 190, 16, 0, 0)),
            arguments("derived/mission-ack-fields.bin", MissionAck(2, 3, 13, 1, OPAQUE_ID)),
        )

        /** At or above 2^31: a signed read of the uint32 would come out negative. */
        const val OPAQUE_ID = 0x8765_4321L
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val VEHICLE_SYSTEM_ID = 1
        const val VEHICLE_COMPONENT_ID = 1
        const val MAV_MISSION_TYPE_MISSION = 0
        const val MAV_FRAME_GLOBAL = 0
        const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        const val MAV_CMD_NAV_WAYPOINT = 16
        const val HOLD_SECONDS = 5f
        const val ALTITUDE = 10f
        const val WAYPOINT_LATITUDE = -353623715
        const val WAYPOINT_LONGITUDE = 1491639215
        const val HOME_LATITUDE = -353633515
        const val HOME_LONGITUDE = 1491652412
        const val HOME_ALTITUDE_AMSL = 585f
        const val MISSION_REQUEST_LIST_ID = 43

        // mission-item-full.bin: frame 255 is past the last MAV_FRAME and command 0x0304 (772) is no MAV_CMD.
        const val UNKNOWN_FRAME = 255

        // mission/session.txt: 35 records, the three MISSION_REQUEST_LISTs undecoded.
        val SESSION_COUNTS = sortedMapOf(
            "MissionCount" to 6,
            "MissionRequest" to 5,
            "MissionItemInt" to 10,
            "MissionAck" to 6,
            "MissionRequestInt" to 5,
            "Undecoded $MISSION_REQUEST_LIST_ID" to 3,
        )

        fun fullItem() = MissionItemInt(
            targetSystem = 2,
            targetComponent = 3,
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
    }
}
