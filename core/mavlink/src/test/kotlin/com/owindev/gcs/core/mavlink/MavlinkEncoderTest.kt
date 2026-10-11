package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
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
 * Encoder tests against real frames: ArduPilot SITL and Mission Planner HEARTBEATs captured in
 * `heartbeat/session.tlog`, and frames packed by pymavlink 2.4.50. Header and field values come from
 * pymavlink's decoding (see `src/test/resources/README.md`), never from the encoder; the expected bytes
 * are the captured or pymavlink-packed frames themselves.
 */
@Tag("REQ-021")
class MavlinkEncoderTest {

    private lateinit var encoder: MavlinkEncoder

    @BeforeEach
    fun setUp() {
        encoder = MavlinkEncoder
    }

    @Test
    fun `GIVEN the fields of the real Mission Planner HEARTBEAT WHEN encoding THEN the bytes match the capture`() {
        val heartbeat = gcsHeartbeat()

        val result = encoder.encode(GCS_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(MISSION_PLANNER)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "heartbeat/disarmed-stabilize.bin, 59, 81, 0",
        "heartbeat/disarmed-guided.bin, 18, 89, 4",
        "heartbeat/disarmed-loiter.bin, 173, 89, 5",
        "heartbeat/armed-guided.bin, 122, 217, 4",
    )
    fun `GIVEN the fields of a real SITL HEARTBEAT WHEN encoding THEN the bytes match the capture`(
        fixture: String,
        sequence: Int,
        baseMode: Int,
        customMode: Long,
    ) {
        val heartbeat = quadrotorHeartbeat(baseMode, customMode)

        val result = encoder.encode(sequence, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(fixture)
    }

    @Test
    fun `GIVEN the largest custom_mode WHEN encoding THEN the bytes match the pymavlink frame`() {
        val heartbeat = quadrotorHeartbeat(DISARMED_STABILIZE_BASE_MODE, MAX_UINT32)

        val result = encoder.encode(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(MAX_CUSTOM_MODE)
    }

    @Test
    fun `GIVEN trailing zero fields WHEN encoding THEN they are truncated like the pymavlink frame`() {
        val heartbeat = Heartbeat(
            type = QUADROTOR,
            autopilot = ARDUPILOTMEGA,
            baseMode = 0,
            customMode = LOITER,
            systemStatus = 0,
            mavlinkVersion = 0,
        )

        val result = encoder.encode(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(TRAILING_ZEROS)
    }

    @Test
    fun `GIVEN every field zero WHEN encoding THEN one payload byte is kept like the pymavlink frame`() {
        val heartbeat =
            Heartbeat(type = 0, autopilot = 0, baseMode = 0, customMode = 0, systemStatus = 0, mavlinkVersion = 0)

        val result = encoder.encode(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(ALL_ZERO)
    }

    @ParameterizedTest(name = "seq {0}")
    @CsvSource("0", "1", "254", "255")
    fun `GIVEN a sequence number WHEN encoding THEN it is the SEQ byte`(sequence: Int) {
        val heartbeat = gcsHeartbeat()

        val result = encoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)

        (result[SEQ_INDEX].toInt() and BYTE_MASK) shouldBeEqualTo sequence
    }

    @Test
    fun `GIVEN an encoded GCS HEARTBEAT WHEN parsing and decoding it THEN the header and fields come back`() {
        val heartbeat = gcsHeartbeat()

        val result = MavlinkParser(MavlinkMessages)
            .parse(encoder.encode(LAST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat))
            .map { frame -> Decoded(frame.sequence, frame.systemId, frame.componentId, MavlinkDecoder.decode(frame)) }

        result shouldBeEqualTo listOf(Decoded(LAST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat))
    }

    @Test
    fun `GIVEN a truncated HEARTBEAT WHEN parsing and decoding it THEN the zero fields come back`() {
        val heartbeat =
            Heartbeat(
                type = QUADROTOR,
                autopilot = 0,
                baseMode = 0,
                customMode = 0,
                systemStatus = 0,
                mavlinkVersion = 0,
            )

        val result = MavlinkParser(MavlinkMessages)
            .parse(encoder.encode(0, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat))
            .map { frame -> MavlinkDecoder.decode(frame) }

        result shouldBeEqualTo listOf(heartbeat)
    }

    @ParameterizedTest(name = "seq {0}, sys {1}, comp {2}")
    @CsvSource(
        "-1, 255, 190",
        "256, 255, 190",
        "0, 0, 190",
        "0, 256, 190",
        "0, 255, 0",
        "0, 255, 256",
    )
    fun `GIVEN a header value out of range WHEN encoding THEN it throws IllegalArgumentException`(
        sequence: Int,
        systemId: Int,
        componentId: Int,
    ) {
        val heartbeat = gcsHeartbeat()

        val result = invoking { encoder.encode(sequence, systemId, componentId, heartbeat) }

        result shouldThrow IllegalArgumentException::class
    }

    @ParameterizedTest(name = "type {0}, autopilot {1}, base_mode {2}, custom_mode {3}")
    @CsvSource(
        "256, 8, 0, 0",
        "6, -1, 0, 0",
        "6, 8, 256, 0",
        "6, 8, 0, 4294967296",
        "6, 8, 0, -1",
    )
    fun `GIVEN a field out of range WHEN encoding THEN it throws IllegalArgumentException`(
        type: Int,
        autopilot: Int,
        baseMode: Int,
        customMode: Long,
    ) {
        val heartbeat =
            Heartbeat(type, autopilot, baseMode, customMode, systemStatus = 0, mavlinkVersion = MAVLINK_VERSION)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat) }

        result shouldThrow IllegalArgumentException::class
    }

    /** What the parser and decoder give back for one frame. */
    private data class Decoded(val sequence: Int, val systemId: Int, val componentId: Int, val message: MavlinkMessage)

    private companion object {
        const val MISSION_PLANNER = "gcs/heartbeat-mission-planner.bin"
        const val MAX_CUSTOM_MODE = "derived/heartbeat-max-custom-mode.bin"
        const val TRAILING_ZEROS = "derived/heartbeat-trailing-zeros.bin"
        const val ALL_ZERO = "derived/heartbeat-all-zero.bin"

        // Mission Planner's HEARTBEAT in session.tlog, as pymavlink decodes it: seq 44, system 255,
        // component 190 (MAV_COMP_ID_MISSIONPLANNER), MAV_TYPE_GCS, MAV_AUTOPILOT_INVALID, the rest 0.
        const val GCS_SEQUENCE = 44
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val MAV_TYPE_GCS = 6
        const val MAV_AUTOPILOT_INVALID = 8

        const val VEHICLE_SYSTEM_ID = 1
        const val VEHICLE_COMPONENT_ID = 1
        const val STABILIZE_SEQUENCE = 59
        const val LAST_SEQUENCE = 255
        const val QUADROTOR = 2
        const val ARDUPILOTMEGA = 3
        const val STANDBY = 3
        const val MAVLINK_VERSION = 3
        const val DISARMED_STABILIZE_BASE_MODE = 81
        const val LOITER = 5L
        const val MAX_UINT32 = 4_294_967_295L
        const val SEQ_INDEX = 4
        const val BYTE_MASK = 0xFF

        fun gcsHeartbeat() = Heartbeat(
            type = MAV_TYPE_GCS,
            autopilot = MAV_AUTOPILOT_INVALID,
            baseMode = 0,
            customMode = 0,
            systemStatus = 0,
            mavlinkVersion = MAVLINK_VERSION,
        )

        fun quadrotorHeartbeat(baseMode: Int, customMode: Long) = Heartbeat(
            type = QUADROTOR,
            autopilot = ARDUPILOTMEGA,
            baseMode = baseMode,
            customMode = customMode,
            systemStatus = STANDBY,
            mavlinkVersion = MAVLINK_VERSION,
        )
    }
}
