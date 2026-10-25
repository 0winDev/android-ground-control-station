package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
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
 * Encoder tests against real frames: ArduPilot SITL and Mission Planner HEARTBEATs and Mission Planner's
 * COMMAND_LONGs captured in `heartbeat/session.tlog`, and frames packed by pymavlink 2.4.50. Header and
 * field values come from pymavlink's decoding (see `src/test/resources/README.md`), never from the
 * encoder; the expected bytes are the captured or pymavlink-packed frames themselves.
 */
class MavlinkEncoderTest {

    private lateinit var encoder: MavlinkEncoder

    @BeforeEach
    fun setUp() {
        encoder = MavlinkEncoder
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the fields of the real Mission Planner HEARTBEAT WHEN encoding THEN the bytes match the capture`() {
        val heartbeat = gcsHeartbeat()

        val result = encoder.encode(GCS_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(MISSION_PLANNER)
    }

    @Tag("REQ-021")
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

    @Tag("REQ-021")
    @Test
    fun `GIVEN the largest custom_mode WHEN encoding THEN the bytes match the pymavlink frame`() {
        val heartbeat = quadrotorHeartbeat(DISARMED_STABILIZE_BASE_MODE, MAX_UINT32)

        val result = encoder.encode(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(MAX_CUSTOM_MODE)
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN an asymmetric custom_mode WHEN encoding THEN its bytes are little-endian like the pymavlink frame`() {
        val heartbeat = quadrotorHeartbeat(DISARMED_STABILIZE_BASE_MODE, ASYMMETRIC_CUSTOM_MODE)

        val result = encoder.encode(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, heartbeat)

        result shouldBeEqualTo readFixture(ASYMMETRIC_CUSTOM_MODE_FRAME)
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN a payload with trailing zero bytes WHEN framing it THEN they are truncated like the pymavlink frame`() {
        val payload = byteArrayOf(LOITER, 0, 0, 0, QUADROTOR_BYTE, ARDUPILOTMEGA_BYTE, 0, 0, 0)

        val result = encoder.frame(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, HEARTBEAT_ID, payload)

        result shouldBeEqualTo readFixture(TRAILING_ZEROS)
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN an all-zero payload WHEN framing it THEN one byte is kept like the pymavlink frame`() {
        val payload = ByteArray(HEARTBEAT_LENGTH)

        val result = encoder.frame(STABILIZE_SEQUENCE, VEHICLE_SYSTEM_ID, VEHICLE_COMPONENT_ID, HEARTBEAT_ID, payload)

        result shouldBeEqualTo readFixture(ALL_ZERO)
    }

    @Tag("REQ-021")
    @ParameterizedTest(name = "seq {0}")
    @CsvSource("0", "1", "254", "255")
    fun `GIVEN a sequence number WHEN encoding THEN it is the SEQ byte`(sequence: Int) {
        val heartbeat = gcsHeartbeat()

        val result = encoder.encode(sequence, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)

        (result[SEQ_INDEX].toInt() and BYTE_MASK) shouldBeEqualTo sequence
    }

    @Tag("REQ-021")
    @Test
    fun `GIVEN an encoded GCS HEARTBEAT WHEN parsing and decoding it THEN the header and fields come back`() {
        val heartbeat = gcsHeartbeat()

        val result = MavlinkParser(MavlinkMessages)
            .parse(encoder.encode(LAST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat))
            .map { frame -> Decoded(frame.sequence, frame.systemId, frame.componentId, MavlinkDecoder.decode(frame)) }

        result shouldBeEqualTo listOf(Decoded(LAST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat))
    }

    @Tag("REQ-021")
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

    @Tag("REQ-021")
    @ParameterizedTest(name = "type {0}, autopilot {1}, base_mode {2}, custom_mode {3}, system_status {4}")
    @CsvSource(
        "256, 8, 0, 0, 0",
        "6, -1, 0, 0, 0",
        "6, 8, 256, 0, 0",
        "6, 8, 0, 4294967296, 0",
        "6, 8, 0, -1, 0",
        "6, 8, 0, 0, 256",
        "6, 8, 0, 0, -1",
    )
    fun `GIVEN a field out of range WHEN encoding THEN it throws IllegalArgumentException`(
        type: Int,
        autopilot: Int,
        baseMode: Int,
        customMode: Long,
        systemStatus: Int,
    ) {
        val heartbeat = Heartbeat(type, autopilot, baseMode, customMode, systemStatus, mavlinkVersion = MAVLINK_VERSION)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-021")
    @ParameterizedTest(name = "mavlink_version {0}")
    @CsvSource("0", "2", "4", "255")
    fun `GIVEN a mavlink_version other than 3 WHEN encoding THEN it throws IllegalArgumentException`(
        mavlinkVersion: Int,
    ) {
        val heartbeat = gcsHeartbeat().copy(mavlinkVersion = mavlinkVersion)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN Mission Planner's real SET_MESSAGE_INTERVAL WHEN encoding THEN the bytes match the capture`() {
        val command = setMessageInterval()

        val result = encoder.encode(SET_MESSAGE_INTERVAL_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, command)

        result shouldBeEqualTo readFixture(SET_MESSAGE_INTERVAL_FRAME)
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN a real COMMAND_LONG with confirmation 1 WHEN encoding THEN the full 33 bytes match the capture`() {
        val command = commandLong(
            command = MAV_CMD_REQUEST_MESSAGE,
            confirmation = 1,
            params = floatArrayOf(CAMERA_INFORMATION_ID, 0f, 0f, 0f, 0f, 0f, 0f),
        )

        val result = encoder.encode(CONFIRMATION_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, command)

        result shouldBeEqualTo readFixture(CONFIRMATION_FRAME)
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN seven distinct params WHEN encoding THEN every slot and byte matches the pymavlink frame`() {
        val command = commandLong(
            command = ARBITRARY_COMMAND,
            confirmation = ARBITRARY_CONFIRMATION,
            params = floatArrayOf(1.5f, -2.25f, Float.NaN, 100_000f, -1f, 0.5f, 65_535f),
        )

        val result = encoder.encode(SET_MESSAGE_INTERVAL_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, command)

        result shouldBeEqualTo readFixture(ALL_PARAMS_FRAME)
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN an encoded SET_MESSAGE_INTERVAL WHEN parsing and decoding it THEN its payload comes back`() {
        val command = setMessageInterval()
        val capturedPayload = wirePayload(readFixture(SET_MESSAGE_INTERVAL_FRAME)).copyOf(COMMAND_LONG_FULL_LENGTH)

        val result = MavlinkParser(MavlinkMessages)
            .parse(encoder.encode(SET_MESSAGE_INTERVAL_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, command))
            .map { frame -> Decoded(frame.sequence, frame.systemId, frame.componentId, MavlinkDecoder.decode(frame)) }

        result shouldBeEqualTo listOf(
            Decoded(
                SET_MESSAGE_INTERVAL_SEQUENCE,
                GCS_SYSTEM_ID,
                GCS_COMPONENT_ID,
                Undecoded(COMMAND_LONG_ID, capturedPayload),
            ),
        )
    }

    @Tag("REQ-022")
    @ParameterizedTest(name = "target {0}/{1}, command {2}, confirmation {3}")
    @CsvSource(
        "-1, 1, 511, 0",
        "256, 1, 511, 0",
        "1, -1, 511, 0",
        "1, 256, 511, 0",
        "1, 1, -1, 0",
        "1, 1, 65536, 0",
        "1, 1, 511, -1",
        "1, 1, 511, 256",
    )
    fun `GIVEN a COMMAND_LONG field out of range WHEN encoding THEN it throws IllegalArgumentException`(
        targetSystem: Int,
        targetComponent: Int,
        command: Int,
        confirmation: Int,
    ) {
        val commandLong = setMessageInterval().copy(
            targetSystem = targetSystem,
            targetComponent = targetComponent,
            command = command,
            confirmation = confirmation,
        )

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, commandLong) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN a broadcast COMMAND_LONG WHEN encoding THEN the zero targets are truncated like the pymavlink frame`() {
        val command = setMessageInterval().copy(targetSystem = 0, targetComponent = 0)

        val result = encoder.encode(SET_MESSAGE_INTERVAL_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, command)

        result shouldBeEqualTo readFixture(BROADCAST_FRAME)
    }

    @Tag("REQ-041")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "BATT_LOW_VOLT, derived/param-request-read-batt-low-volt.bin",
        "INS_GYR1_CALTEMP, derived/param-request-read-16-chars.bin",
        "A, derived/param-request-read-1-char.bin",
    )
    fun `GIVEN a parameter name WHEN encoding a PARAM_REQUEST_READ THEN the bytes match the pymavlink frame`(
        paramId: String,
        fixture: String,
    ) {
        val request = paramRequestRead(paramId)

        val result = encoder.encode(PARAM_REQUEST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, request)

        result shouldBeEqualTo readFixture(fixture)
    }

    @Tag("REQ-041")
    @ParameterizedTest(name = "target {0}/{1}")
    @CsvSource(
        "-1, 1",
        "256, 1",
        "1, -1",
        "1, 256",
    )
    fun `GIVEN a PARAM_REQUEST_READ target out of range WHEN encoding THEN it throws IllegalArgumentException`(
        targetSystem: Int,
        targetComponent: Int,
    ) {
        val request = ParamRequestRead(targetSystem, targetComponent, BATT_LOW_VOLT)

        val result = invoking { encoder.encode(0, GCS_SYSTEM_ID, GCS_COMPONENT_ID, request) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-041")
    @Test
    fun `GIVEN an encoded PARAM_REQUEST_READ WHEN parsing and decoding it THEN it stays undecoded and zero-filled`() {
        val request = paramRequestRead(BATT_LOW_VOLT)
        val pymavlinkPayload = wirePayload(readFixture(PARAM_REQUEST_BATT_LOW_VOLT))
            .copyOf(PARAM_REQUEST_READ_FULL_LENGTH)

        val result = MavlinkParser(MavlinkMessages)
            .parse(encoder.encode(PARAM_REQUEST_SEQUENCE, GCS_SYSTEM_ID, GCS_COMPONENT_ID, request))
            .map { frame -> Decoded(frame.sequence, frame.systemId, frame.componentId, MavlinkDecoder.decode(frame)) }

        result shouldBeEqualTo listOf(
            Decoded(
                PARAM_REQUEST_SEQUENCE,
                GCS_SYSTEM_ID,
                GCS_COMPONENT_ID,
                Undecoded(PARAM_REQUEST_READ_ID, pymavlinkPayload),
            ),
        )
    }

    private fun wirePayload(frame: ByteArray): ByteArray =
        frame.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + (frame[LEN_INDEX].toInt() and BYTE_MASK))

    /** What the parser and decoder give back for one frame. */
    private data class Decoded(val sequence: Int, val systemId: Int, val componentId: Int, val message: MavlinkMessage)

    private companion object {
        const val MISSION_PLANNER = "gcs/heartbeat-mission-planner.bin"
        const val MAX_CUSTOM_MODE = "derived/heartbeat-max-custom-mode.bin"
        const val TRAILING_ZEROS = "derived/heartbeat-trailing-zeros.bin"
        const val ALL_ZERO = "derived/heartbeat-all-zero.bin"
        const val ASYMMETRIC_CUSTOM_MODE_FRAME = "derived/heartbeat-asymmetric-custom-mode.bin"

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
        const val LOITER: Byte = 5
        const val QUADROTOR_BYTE: Byte = 2
        const val ARDUPILOTMEGA_BYTE: Byte = 3
        const val HEARTBEAT_ID = 0
        const val HEARTBEAT_LENGTH = 9
        const val ASYMMETRIC_CUSTOM_MODE = 0x1234_5678L
        const val MAX_UINT32 = 4_294_967_295L
        const val SEQ_INDEX = 4
        const val BYTE_MASK = 0xFF
        const val HEADER_LENGTH = 10
        const val LEN_INDEX = 1

        const val SET_MESSAGE_INTERVAL_FRAME = "command/set-message-interval.bin"
        const val CONFIRMATION_FRAME = "command/command-long-confirmation.bin"
        const val ALL_PARAMS_FRAME = "derived/command-long-all-params.bin"
        const val BROADCAST_FRAME = "derived/command-long-broadcast.bin"
        const val COMMAND_LONG_ID = 76
        const val COMMAND_LONG_FULL_LENGTH = 33

        // Mission Planner's COMMAND_LONGs in session.tlog, as pymavlink decodes them (see the fixtures
        // README): SET_MESSAGE_INTERVAL (seq 81) for message 271 every 200000 µs, and a resent
        // REQUEST_MESSAGE (seq 138) for message 259 with confirmation 1, both to system 1, component 1.
        const val SET_MESSAGE_INTERVAL_SEQUENCE = 81
        const val CONFIRMATION_SEQUENCE = 138
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_CMD_REQUEST_MESSAGE = 512
        const val CAMERA_FOV_STATUS_ID = 271f
        const val INTERVAL_US = 200_000f
        const val CAMERA_INFORMATION_ID = 259f

        // pymavlink-derived frame: not a real command, every byte distinct.
        const val ARBITRARY_COMMAND = 0x1234
        const val ARBITRARY_CONFIRMATION = 7

        // pymavlink-built PARAM_REQUEST_READs (see the fixtures README): seq 42, from 255/190 to 1/1, by name.
        const val PARAM_REQUEST_BATT_LOW_VOLT = "derived/param-request-read-batt-low-volt.bin"
        const val PARAM_REQUEST_SEQUENCE = 42
        const val PARAM_REQUEST_READ_ID = 20
        const val PARAM_REQUEST_READ_FULL_LENGTH = 20
        const val BATT_LOW_VOLT = "BATT_LOW_VOLT"

        fun paramRequestRead(paramId: String) = ParamRequestRead(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = VEHICLE_COMPONENT_ID,
            paramId = paramId,
        )

        fun setMessageInterval() = commandLong(
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            confirmation = 0,
            params = floatArrayOf(CAMERA_FOV_STATUS_ID, INTERVAL_US, 0f, 0f, 0f, 0f, 0f),
        )

        fun commandLong(command: Int, confirmation: Int, params: FloatArray) = CommandLong(
            targetSystem = VEHICLE_SYSTEM_ID,
            targetComponent = VEHICLE_COMPONENT_ID,
            command = command,
            confirmation = confirmation,
            param1 = params[0],
            param2 = params[1],
            param3 = params[2],
            param4 = params[3],
            param5 = params[4],
            param6 = params[5],
            param7 = params[6],
        )

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
