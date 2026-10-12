package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import com.owindev.gcs.core.testing.readFixture
import kotlin.random.Random
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Decoder tests from real ArduPilot SITL frames run through the parser with the codec's message table.
 * Expected values come from pymavlink 2.4.50 (see `src/test/resources/README.md`), never from the decoder.
 */
class MavlinkDecoderTest {

    private lateinit var decoder: MavlinkDecoder

    @BeforeEach
    fun setUp() {
        decoder = MavlinkDecoder
    }

    @Tag("REQ-015")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "heartbeat/disarmed-stabilize.bin, 81, 0",
        "heartbeat/disarmed-guided.bin, 89, 4",
        "heartbeat/disarmed-loiter.bin, 89, 5",
        "heartbeat/armed-guided.bin, 217, 4",
    )
    fun `GIVEN a real SITL HEARTBEAT WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        baseMode: Int,
        customMode: Long,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo quadrotorHeartbeat(baseMode, customMode)
    }

    @Tag("REQ-015")
    @Test
    fun `GIVEN a HEARTBEAT with the largest custom_mode WHEN decoding THEN custom_mode is an unsigned 32-bit value`() {
        val frame = parsedFrame(MAX_CUSTOM_MODE)

        val result = decoder.decode(frame)

        result shouldBeEqualTo quadrotorHeartbeat(DISARMED_STABILIZE_BASE_MODE, MAX_UINT32)
    }

    @Tag("REQ-013")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "truncated/sys_status.bin, 1, 43",
        "truncated/power_status.bin, 125, 6",
        "truncated/servo_output_raw.bin, 36, 37",
        "command/set-message-interval.bin, 76, 33",
    )
    fun `GIVEN a real truncated frame WHEN decoding THEN its wire payload is zero-filled to the full length`(
        fixture: String,
        messageId: Int,
        fullLength: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo Undecoded(messageId, wirePayload(readFixture(fixture)).copyOf(fullLength))
    }

    @Tag("REQ-013")
    @Test
    fun `GIVEN the truncated POWER_STATUS WHEN decoding THEN the restored payload is Vcc 5000 and zeros`() {
        val frame = parsedFrame(POWER_STATUS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo Undecoded(POWER_STATUS_ID, restoredPowerStatus)
    }

    @Tag("REQ-013")
    @Test
    fun `GIVEN the truncated SYS_STATUS WHEN decoding THEN its three extension fields are restored as zeros`() {
        val fixture = readFixture(SYS_STATUS)

        val result = decoder.decode(parsedFrame(SYS_STATUS))

        result shouldBeEqualTo Undecoded(SYS_STATUS_ID, wirePayload(fixture) + ByteArray(SYS_STATUS_EXTENSION_BYTES))
    }

    @Tag("REQ-013")
    @Test
    fun `GIVEN a HEARTBEAT payload cut after type WHEN decoding THEN the missing fields read as zero`() {
        val frame = frameOf(HEARTBEAT_ID, byteArrayOf(0x04, 0x00, 0x00, 0x00, QUADROTOR.toByte()))

        val result = decoder.decode(frame)

        result shouldBeEqualTo Heartbeat(
            type = QUADROTOR,
            autopilot = 0,
            baseMode = 0,
            customMode = 4,
            systemStatus = 0,
            mavlinkVersion = 0,
        )
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN ArduCopter's real ACK to SET_MESSAGE_INTERVAL WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame(SET_MESSAGE_INTERVAL_ACK)

        val result = decoder.decode(frame)

        result shouldBeEqualTo CommandAck(
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            result = MAV_RESULT_ACCEPTED,
            progress = 0,
            resultParam2 = 0,
            targetSystem = GCS_SYSTEM_ID,
            targetComponent = GCS_COMPONENT_ID,
        )
    }

    @Tag("REQ-022")
    @Test
    fun `GIVEN an ACK with every extension set WHEN decoding THEN progress and a negative result_param2 come out`() {
        val frame = parsedFrame(ACK_EXTENSIONS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo CommandAck(
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            result = MAV_RESULT_IN_PROGRESS,
            progress = IN_PROGRESS_PERCENT,
            resultParam2 = NEGATIVE_RESULT_PARAM2,
            targetSystem = GCS_SYSTEM_ID,
            targetComponent = GCS_COMPONENT_ID,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-022")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "derived/command-ack-accepted-no-extensions.bin, 0",
        "derived/command-ack-failed-no-extensions.bin, 4",
    )
    fun `GIVEN an ACK truncated before its extensions WHEN decoding THEN the missing fields read as zero`(
        fixture: String,
        ackResult: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo CommandAck(
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            result = ackResult,
            progress = 0,
            resultParam2 = 0,
            targetSystem = 0,
            targetComponent = 0,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-022")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "derived/command-ack-split-param2.bin, 256, 0",
        "derived/command-ack-target-system-only.bin, 0, 255",
    )
    fun `GIVEN an ACK truncated inside its extensions WHEN decoding THEN only the cut bytes read as zero`(
        fixture: String,
        resultParam2: Int,
        targetSystem: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo CommandAck(
            command = MAV_CMD_SET_MESSAGE_INTERVAL,
            result = MAV_RESULT_ACCEPTED,
            progress = 0,
            resultParam2 = resultParam2,
            targetSystem = targetSystem,
            targetComponent = 0,
        )
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN frames with random IDs and payloads of any size WHEN decoding THEN nothing is thrown`() {
        val frames = randomFrames(Random(SEED))

        val result = invoking { frames.forEach { decoder.decode(it) } }

        result shouldNotThrow AnyException
    }

    @Tag("REQ-013")
    @Test
    fun `GIVEN a HEARTBEAT with an unknown extension byte WHEN decoding THEN the extra byte is ignored`() {
        val frame = parsedFrame(EXTRA_BYTE)

        val result = decoder.decode(frame)

        result shouldBeEqualTo quadrotorHeartbeat(DISARMED_STABILIZE_BASE_MODE, 0)
    }

    @Test
    fun `GIVEN a frame whose message is not in the table WHEN decoding THEN it is undecoded with its payload as is`() {
        val attitude = readFixture(ATTITUDE)
        val frame = frameOf(ATTITUDE_ID, wirePayload(attitude))

        val result = decoder.decode(frame)

        result shouldBeEqualTo Undecoded(ATTITUDE_ID, wirePayload(attitude))
    }

    @Tag("REQ-013")
    @Tag("REQ-015")
    @Test
    fun `GIVEN the SITL session WHEN parsing and decoding THEN each table message comes out as pymavlink counts`() {
        val parser = MavlinkParser(MavlinkMessages)
        val frames = chunks(readFixture(SESSION_STREAM), Random(SEED)).flatMap { parser.parse(it) }

        val result = frames.map { decoder.decode(it) }.groupingBy { it.kind() }.eachCount().toSortedMap()

        result shouldBeEqualTo sessionCounts
        parser.stats shouldBeEqualTo
            ParserStats(unknownMessageId = SESSION_UNKNOWN, skippedBytes = SESSION_MAVLINK1_BYTES)
    }

    private fun parsedFrame(fixture: String): MavlinkFrame =
        MavlinkParser(MavlinkMessages).parse(readFixture(fixture)).single()

    private fun wirePayload(frame: ByteArray): ByteArray =
        frame.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + (frame[LEN_INDEX].toInt() and BYTE_MASK))

    private fun chunks(stream: ByteArray, random: Random): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < stream.size) {
            val end = minOf(stream.size, start + random.nextInt(1, MAX_DATAGRAM + 1))
            chunks += stream.copyOfRange(start, end)
            start = end
        }
        return chunks
    }

    private fun randomFrames(random: Random): List<MavlinkFrame> = List(FUZZ_FRAMES) {
        val messageId = FUZZ_IDS.getOrElse(random.nextInt(FUZZ_IDS.size + 1)) { random.nextInt(MAX_MESSAGE_ID + 1) }
        frameOf(messageId, random.nextBytes(random.nextInt(MAX_PAYLOAD + 1)))
    }

    private fun MavlinkMessage.kind(): String = when (this) {
        is Heartbeat -> "HEARTBEAT"
        is CommandAck -> "COMMAND_ACK"
        is CommandLong -> "COMMAND_LONG"
        is Undecoded -> "$messageId/${payload.size}"
    }

    private companion object {
        const val MAX_CUSTOM_MODE = "derived/heartbeat-max-custom-mode.bin"
        const val EXTRA_BYTE = "derived/heartbeat-extra-byte.bin"
        const val POWER_STATUS = "truncated/power_status.bin"
        const val SYS_STATUS = "truncated/sys_status.bin"
        const val ATTITUDE = "unknown/attitude-fd-in-payload.bin"
        const val SESSION_STREAM = "derived/session-stream.bin"
        const val SET_MESSAGE_INTERVAL_ACK = "command/set-message-interval-ack.bin"
        const val ACK_EXTENSIONS = "derived/command-ack-extensions.bin"

        // Field values of the COMMAND_ACK fixtures as pymavlink decodes them (see the fixtures README).
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_IN_PROGRESS = 5
        const val IN_PROGRESS_PERCENT = 50
        const val NEGATIVE_RESULT_PARAM2 = -123_456
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val POWER_STATUS_ID = 125
        const val SYS_STATUS_ID = 1
        const val HEARTBEAT_ID = 0

        // onboard_control_sensors_present/enabled/health_extended: three uint32 extensions, 0 in pymavlink.
        const val SYS_STATUS_EXTENSION_BYTES = 12
        const val FUZZ_FRAMES = 10_000
        const val MAX_PAYLOAD = 255
        const val MAX_MESSAGE_ID = 0xFF_FFFF
        val FUZZ_IDS = listOf(0, 1, 36, 76, 77, 125)
        const val ATTITUDE_ID = 30
        const val QUADROTOR = 2
        const val ARDUPILOTMEGA = 3
        const val STANDBY = 3
        const val MAVLINK_VERSION = 3
        const val DISARMED_STABILIZE_BASE_MODE = 81
        const val MAX_UINT32 = 4_294_967_295L
        const val SESSION_UNKNOWN = 4_585L
        const val SESSION_MAVLINK1_BYTES = 82L
        const val HEADER_LENGTH = 10
        const val LEN_INDEX = 1
        const val BYTE_MASK = 0xFF
        const val SEED = 10
        const val MAX_DATAGRAM = 300

        // Counts printed by tools/fixtures/derive_fixtures.py (pymavlink); undecoded ones keyed by
        // "msgid/restored length", so every one of them is checked to be zero-filled to its full length.
        val sessionCounts = sortedMapOf(
            "HEARTBEAT" to 133,
            "1/43" to 325,
            "36/37" to 130,
            "125/6" to 325,
            "76/33" to 11,
            "COMMAND_ACK" to 13,
        )

        // Vcc = 5000 (0x1388, little-endian), Vservo = 0, flags = 0, as pymavlink decodes it.
        val restoredPowerStatus = byteArrayOf(0x88.toByte(), 0x13, 0x00, 0x00, 0x00, 0x00)

        fun quadrotorHeartbeat(baseMode: Int, customMode: Long) = Heartbeat(
            type = QUADROTOR,
            autopilot = ARDUPILOTMEGA,
            baseMode = baseMode,
            customMode = customMode,
            systemStatus = STANDBY,
            mavlinkVersion = MAVLINK_VERSION,
        )

        fun frameOf(messageId: Int, payload: ByteArray) = MavlinkFrame(
            sequence = 0,
            systemId = 1,
            componentId = 1,
            messageId = messageId,
            incompatFlags = 0,
            compatFlags = 0,
            payload = payload,
            signature = null,
        )
    }
}
