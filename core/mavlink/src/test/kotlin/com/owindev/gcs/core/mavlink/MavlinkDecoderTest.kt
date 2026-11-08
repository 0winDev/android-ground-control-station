package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Attitude
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandAck
import com.owindev.gcs.core.mavlink.MavlinkMessage.CommandLong
import com.owindev.gcs.core.mavlink.MavlinkMessage.ExtendedSysState
import com.owindev.gcs.core.mavlink.MavlinkMessage.GlobalPositionInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.GpsRawInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat
import com.owindev.gcs.core.mavlink.MavlinkMessage.HomePosition
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue
import com.owindev.gcs.core.mavlink.MavlinkMessage.StatusText
import com.owindev.gcs.core.mavlink.MavlinkMessage.SysStatus
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import com.owindev.gcs.core.mavlink.MavlinkMessage.VfrHud
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
    @Tag("REQ-025")
    @Test
    fun `GIVEN a real SITL SYS_STATUS WHEN decoding THEN it has the fields pymavlink decodes and zero extensions`() {
        val frame = parsedFrame(SYS_STATUS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlSysStatus
    }

    @Tag("REQ-025")
    @Test
    fun `GIVEN a SYS_STATUS with battery_remaining -128 WHEN decoding THEN the int8 is sign-extended`() {
        val frame = parsedFrame(SYS_STATUS_BATTERY_REMAINING_MIN)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlSysStatus.copy(batteryRemaining = INT8_MIN)
    }

    @Tag("REQ-013")
    @Tag("REQ-025")
    @Test
    fun `GIVEN the real boot SYS_STATUS without a battery reading WHEN decoding THEN current and remaining are -1`() {
        val frame = parsedFrame(BOOT_SYS_STATUS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo bootSysStatus(voltageBattery = 0)
    }

    @Tag("REQ-025")
    @Test
    fun `GIVEN a SYS_STATUS with an unknown voltage WHEN decoding THEN voltage_battery stays UINT16_MAX`() {
        val frame = parsedFrame(SYS_STATUS_UNKNOWN_BATTERY)

        val result = decoder.decode(frame)

        result shouldBeEqualTo bootSysStatus(voltageBattery = UINT16_MAX)
    }

    @Tag("REQ-025")
    @Test
    fun `GIVEN a SYS_STATUS with a distinct value in every field WHEN decoding THEN each lands in its field`() {
        val frame = parsedFrame(SYS_STATUS_EDGES)

        val result = decoder.decode(frame)

        result shouldBeEqualTo SysStatus(
            onboardControlSensorsPresent = BOOT_SENSORS_PRESENT,
            onboardControlSensorsEnabled = BOOT_SENSORS_ENABLED,
            onboardControlSensorsHealth = BOOT_SENSORS_HEALTH,
            load = 500,
            voltageBattery = 11_100,
            currentBattery = 1_550,
            batteryRemaining = 42,
            dropRateComm = 250,
            errorsComm = 1,
            errorsCount1 = 2,
            errorsCount2 = 3,
            errorsCount3 = 4,
            errorsCount4 = 5,
            onboardControlSensorsPresentExtended = 0x0102_0304L,
            onboardControlSensorsEnabledExtended = 0x0506_0708L,
            onboardControlSensorsHealthExtended = 0x89AB_CDEFL,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-025")
    @Test
    fun `GIVEN a real SITL GPS_RAW_INT cut inside v_acc WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame(GPS_RAW_INT)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlGpsRawInt
    }

    @Tag("REQ-025")
    @Test
    fun `GIVEN a GPS_RAW_INT with time_usec 2^63 WHEN decoding THEN it comes out negative as documented`() {
        val frame = parsedFrame(GPS_RAW_INT_TIME_2_63)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlGpsRawInt.copy(timeUsec = Long.MIN_VALUE)
    }

    @Tag("REQ-025")
    @Test
    fun `GIVEN a GPS_RAW_INT with a distinct value in every field WHEN decoding THEN each lands in its field`() {
        val frame = parsedFrame(GPS_RAW_INT_EDGES)

        val result = decoder.decode(frame)

        result shouldBeEqualTo GpsRawInt(
            timeUsec = 0x0123_4567_89AB_CDEFL,
            fixType = GPS_FIX_TYPE_3D_FIX,
            lat = SITL_GPS_LAT,
            lon = SITL_GPS_LON,
            alt = -12_345,
            eph = 150,
            epv = 250,
            vel = 1_234,
            cog = 35_999,
            satellitesVisible = 12,
            altEllipsoid = -23_456,
            hAcc = 0x89AB_CDEFL,
            vAcc = 400,
            velAcc = 50,
            hdgAcc = 12_345,
            yaw = 36_000,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-025")
    @Test
    fun `GIVEN a GPS_RAW_INT with every unknown value and no extensions WHEN decoding THEN the sentinels hold`() {
        val frame = parsedFrame(GPS_RAW_INT_UNKNOWN)

        val result = decoder.decode(frame)

        result shouldBeEqualTo GpsRawInt(
            timeUsec = SITL_GPS_TIME_USEC,
            fixType = GPS_FIX_TYPE_NO_GPS,
            lat = SITL_GPS_LAT,
            lon = SITL_GPS_LON,
            alt = SITL_GPS_ALT,
            eph = UINT16_MAX,
            epv = UINT16_MAX,
            vel = UINT16_MAX,
            cog = UINT16_MAX,
            satellitesVisible = UINT8_MAX,
            altEllipsoid = 0,
            hAcc = 0,
            vAcc = 0,
            velAcc = 0,
            hdgAcc = 0,
            yaw = 0,
        )
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

    @Tag("REQ-024")
    @Test
    fun `GIVEN a real SITL ATTITUDE WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame(ATTITUDE)

        val result = decoder.decode(frame)

        result shouldBeEqualTo Attitude(
            timeBootMs = 397_119,
            roll = -0.0010504957f,
            pitch = -0.0012832552f,
            yaw = -0.011366294f,
            rollspeed = -0.00022312743f,
            pitchspeed = -0.00022124127f,
            yawspeed = -0.0007730834f,
        )
    }

    @Tag("REQ-024")
    @Test
    fun `GIVEN an ATTITUDE with distinct floats WHEN decoding THEN each lands in its field and time is unsigned`() {
        val frame = parsedFrame(ATTITUDE_EDGES)

        val result = decoder.decode(frame)

        result shouldBeEqualTo Attitude(
            timeBootMs = LARGE_TIME_BOOT_MS,
            roll = 0.5f,
            pitch = -0.25f,
            yaw = -3.1415927f,
            rollspeed = 1.5f,
            pitchspeed = -0.75f,
            yawspeed = 0.125f,
        )
    }

    @Tag("REQ-024")
    @Test
    fun `GIVEN a real SITL GLOBAL_POSITION_INT WHEN decoding THEN it has the fields pymavlink decodes`() {
        val frame = parsedFrame(GLOBAL_POSITION_INT)

        val result = decoder.decode(frame)

        result shouldBeEqualTo GlobalPositionInt(
            timeBootMs = 397_289,
            lat = SITL_LAT,
            lon = SITL_LON,
            alt = 587_130,
            relativeAlt = -13,
            vx = 1,
            vy = -1,
            vz = 0,
            hdg = 35_935,
        )
    }

    @Tag("REQ-024")
    @Test
    fun `GIVEN a GLOBAL_POSITION_INT with negatives and unknown hdg WHEN decoding THEN signs and UINT16_MAX hold`() {
        val frame = parsedFrame(GLOBAL_POSITION_INT_EDGES)

        val result = decoder.decode(frame)

        result shouldBeEqualTo GlobalPositionInt(
            timeBootMs = LARGE_TIME_BOOT_MS,
            lat = SITL_LAT,
            lon = SITL_LON,
            alt = -12_345,
            relativeAlt = -500,
            vx = -300,
            vy = 1_234,
            vz = -150,
            hdg = UINT16_MAX,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-024")
    @Test
    fun `GIVEN a real SITL VFR_HUD truncated before throttle WHEN decoding THEN throttle reads as zero`() {
        val frame = parsedFrame(VFR_HUD)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlVfrHud(throttle = 0, climb = -0.00015098024f)
    }

    @Tag("REQ-013")
    @Tag("REQ-024")
    @Test
    fun `GIVEN a VFR_HUD truncated inside throttle WHEN decoding THEN only its high byte reads as zero`() {
        val frame = parsedFrame(VFR_HUD_EDGES)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlVfrHud(throttle = 65, climb = -2.5f)
    }

    @Tag("REQ-013")
    @Tag("REQ-032")
    @Test
    fun `GIVEN a real SITL HOME_POSITION cut inside time_usec WHEN decoding THEN has the fields pymavlink decodes`() {
        val frame = parsedFrame(HOME_POSITION)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlHomePosition
    }

    @Tag("REQ-032")
    @Test
    fun `GIVEN a full HOME_POSITION with a distinct value in every field WHEN decoding THEN each lands in its field`() {
        val frame = parsedFrame(HOME_POSITION_FULL)

        val result = decoder.decode(frame)

        result shouldBeEqualTo HomePosition(
            latitude = SITL_LAT,
            longitude = -1_491_652_411,
            altitude = -12_345,
            x = 1.5f,
            y = -2.25f,
            z = 0.75f,
            q1 = 0.5f,
            q2 = -0.25f,
            q3 = 0.125f,
            q4 = -0.0625f,
            approachX = 3.5f,
            approachY = -4.5f,
            approachZ = 5.25f,
            timeUsec = 0x0102_0304_0506_0708L,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-032")
    @Test
    fun `GIVEN a HOME_POSITION without its extension WHEN decoding THEN time_usec reads as zero`() {
        val frame = parsedFrame(HOME_POSITION_NO_EXTENSION)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlHomePosition.copy(approachZ = -1.5f, timeUsec = 0)
    }

    @Tag("REQ-013")
    @Tag("REQ-032")
    @Test
    fun `GIVEN a HOME_POSITION truncated after q WHEN decoding THEN the approach and time_usec read as zero`() {
        val frame = parsedFrame(HOME_POSITION_CUT_AFTER_Q)

        val result = decoder.decode(frame)

        result shouldBeEqualTo sitlHomePosition.copy(timeUsec = 0)
    }

    @Tag("REQ-013")
    @Tag("REQ-040")
    @Test
    fun `GIVEN a real SITL STATUSTEXT truncated without extensions WHEN decoding THEN it is a single chunk`() {
        val frame = parsedFrame(STATUSTEXT_ARMING_MOTORS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(
            severity = INFO,
            text = "Arming motors",
            id = 0,
            chunkSeq = 0,
            endsInField = true,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-040")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "derived/session-stream.bin, statustext/session.tsv",
        "tlog/mission-planner.frames.bin, statustext/mission-planner.tsv",
    )
    fun `GIVEN every real STATUSTEXT of a SITL log WHEN decoding THEN each is what pymavlink decodes`(
        frames: String,
        expected: String,
    ) {
        val parsed = MavlinkParser(MavlinkMessages).parse(readFixture(frames))

        val result = parsed.map { decoder.decode(it) }.filterIsInstance<StatusText>()

        result shouldBeEqualTo statusTextsOf(expected)
    }

    @Tag("REQ-040")
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        "derived/statustext-prearm.bin, PreArm: RC not found",
        "derived/statustext-arm.bin, Arm: RTL mode not armable",
    )
    fun `GIVEN an ArduCopter arming failure WHEN decoding THEN has its CRITICAL severity and text`(
        fixture: String,
        text: String,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(severity = CRITICAL, text = text, id = 0, chunkSeq = 0, endsInField = true)
    }

    @Tag("REQ-040")
    @Test
    fun `GIVEN a full STATUSTEXT with a 50-byte text WHEN decoding THEN each field lands in its place`() {
        val frame = parsedFrame(STATUSTEXT_FULL)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(
            severity = WARNING,
            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx",
            id = 0x1234,
            chunkSeq = 7,
            endsInField = false,
        )
    }

    @Tag("REQ-040")
    @ParameterizedTest(name = "chunk {2}")
    @CsvSource(
        "derived/statustext-chunk-1.bin, A STATUSTEXT longer than fifty bytes goes out in c, 0, false",
        "derived/statustext-chunk-2.bin, hunks of fifty, 1, true",
    )
    fun `GIVEN a chunk of a long STATUSTEXT WHEN decoding THEN has its part of the text and whether it is the last`(
        fixture: String,
        text: String,
        chunkSeq: Int,
        endsInField: Boolean,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(
            severity = INFO,
            text = text,
            id = CHUNK_ID,
            chunkSeq = chunkSeq,
            endsInField = endsInField,
        )
    }

    @Tag("REQ-040")
    @Test
    fun `GIVEN a STATUSTEXT with bytes after a NUL WHEN decoding THEN the text ends at the NUL`() {
        val frame = parsedFrame(STATUSTEXT_NUL_INSIDE)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(severity = INFO, text = "ab", id = 0, chunkSeq = 0, endsInField = true)
    }

    @Tag("REQ-013")
    @Tag("REQ-040")
    @Test
    fun `GIVEN a STATUSTEXT with no text, truncated to its severity WHEN decoding THEN the text is empty`() {
        val frame = parsedFrame(STATUSTEXT_EMPTY)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(severity = EMERGENCY, text = "", id = 0, chunkSeq = 0, endsInField = true)
    }

    @Tag("REQ-040")
    @Test
    fun `GIVEN a STATUSTEXT with a severity outside MAV_SEVERITY WHEN decoding THEN it is kept as sent`() {
        val frame = parsedFrame(STATUSTEXT_SEVERITY_8)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(
            severity = OUTSIDE_SEVERITY,
            text = "Arming motors",
            id = 0,
            chunkSeq = 0,
            endsInField = true,
        )
    }

    @Tag("REQ-012")
    @Tag("REQ-040")
    @Test
    fun `GIVEN a STATUSTEXT with UTF-8 and an invalid byte WHEN decoding THEN the invalid byte is a replacement`() {
        val frame = parsedFrame(STATUSTEXT_UTF8)

        val result = decoder.decode(frame)

        result shouldBeEqualTo StatusText(
            severity = INFO,
            text = "caf\u00e9 \ufffd ok",
            id = 0,
            chunkSeq = 0,
            endsInField = true,
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
        val scaledPressure = readFixture(SCALED_PRESSURE)
        val frame = frameOf(SCALED_PRESSURE_ID, wirePayload(scaledPressure))

        val result = decoder.decode(frame)

        result shouldBeEqualTo Undecoded(SCALED_PRESSURE_ID, wirePayload(scaledPressure))
    }

    @Tag("REQ-013")
    @Tag("REQ-015")
    @Tag("REQ-024")
    @Tag("REQ-025")
    @Tag("REQ-032")
    @Tag("REQ-040")
    @Tag("REQ-041")
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

    private fun statusTextsOf(table: String): List<StatusText> =
        readFixture(table).decodeToString().lines().drop(1).filter { it.isNotEmpty() }.map { line ->
            val columns = line.split(TAB)
            StatusText(
                severity = columns[SEVERITY_COLUMN].toInt(),
                text = columns[TEXT_COLUMN],
                id = columns[ID_COLUMN].toInt(),
                chunkSeq = columns[CHUNK_SEQ_COLUMN].toInt(),
                endsInField = columns[ENDS_IN_FIELD_COLUMN].toBooleanStrict(),
            )
        }

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

    private fun MavlinkMessage.kind(): String =
        if (this is Undecoded) "$messageId/${payload.size}" else DECODED_NAMES.getValue(this::class)

    private companion object {
        val DECODED_NAMES = mapOf(
            Heartbeat::class to "HEARTBEAT",
            SysStatus::class to "SYS_STATUS",
            GpsRawInt::class to "GPS_RAW_INT",
            Attitude::class to "ATTITUDE",
            GlobalPositionInt::class to "GLOBAL_POSITION_INT",
            VfrHud::class to "VFR_HUD",
            CommandAck::class to "COMMAND_ACK",
            CommandLong::class to "COMMAND_LONG",
            HomePosition::class to "HOME_POSITION",
            StatusText::class to "STATUSTEXT",
            ParamRequestRead::class to "PARAM_REQUEST_READ",
            ParamValue::class to "PARAM_VALUE",
            ExtendedSysState::class to "EXTENDED_SYS_STATE",
        )
        const val MAX_CUSTOM_MODE = "derived/heartbeat-max-custom-mode.bin"
        const val EXTRA_BYTE = "derived/heartbeat-extra-byte.bin"
        const val POWER_STATUS = "truncated/power_status.bin"
        const val SYS_STATUS = "telemetry/sys-status.bin"
        const val BOOT_SYS_STATUS = "truncated/sys_status.bin"
        const val SYS_STATUS_EDGES = "derived/sys-status-edges.bin"
        const val SYS_STATUS_UNKNOWN_BATTERY = "derived/sys-status-unknown-battery.bin"
        const val GPS_RAW_INT = "telemetry/gps-raw-int.bin"
        const val GPS_RAW_INT_EDGES = "derived/gps-raw-int-edges.bin"
        const val GPS_RAW_INT_UNKNOWN = "derived/gps-raw-int-unknown.bin"
        const val SYS_STATUS_BATTERY_REMAINING_MIN = "derived/sys-status-battery-remaining-min.bin"
        const val GPS_RAW_INT_TIME_2_63 = "derived/gps-raw-int-time-2-63.bin"
        const val SCALED_PRESSURE = "unknown/scaled-pressure.bin"
        const val ATTITUDE = "telemetry/attitude.bin"
        const val ATTITUDE_EDGES = "derived/attitude-edges.bin"
        const val GLOBAL_POSITION_INT = "telemetry/global-position-int.bin"
        const val GLOBAL_POSITION_INT_EDGES = "derived/global-position-int-edges.bin"
        const val VFR_HUD = "telemetry/vfr-hud.bin"
        const val VFR_HUD_EDGES = "derived/vfr-hud-edges.bin"
        const val SESSION_STREAM = "derived/session-stream.bin"
        const val SET_MESSAGE_INTERVAL_ACK = "command/set-message-interval-ack.bin"
        const val ACK_EXTENSIONS = "derived/command-ack-extensions.bin"
        const val HOME_POSITION = "telemetry/home-position.bin"
        const val HOME_POSITION_FULL = "derived/home-position-full.bin"
        const val HOME_POSITION_NO_EXTENSION = "derived/home-position-no-extension.bin"
        const val HOME_POSITION_CUT_AFTER_Q = "derived/home-position-cut-after-q.bin"
        const val STATUSTEXT_ARMING_MOTORS = "statustext/arming-motors.bin"
        const val STATUSTEXT_FULL = "derived/statustext-full.bin"
        const val STATUSTEXT_NUL_INSIDE = "derived/statustext-nul-inside.bin"
        const val STATUSTEXT_UTF8 = "derived/statustext-utf8.bin"
        const val STATUSTEXT_EMPTY = "derived/statustext-empty.bin"
        const val STATUSTEXT_SEVERITY_8 = "derived/statustext-severity-8.bin"
        const val TAB = "\t"
        const val EMERGENCY = 0
        const val CRITICAL = 2
        const val WARNING = 4
        const val INFO = 6
        const val CHUNK_ID = 1
        const val OUTSIDE_SEVERITY = 8
        const val SEVERITY_COLUMN = 0
        const val TEXT_COLUMN = 1
        const val ID_COLUMN = 2
        const val CHUNK_SEQ_COLUMN = 3
        const val ENDS_IN_FIELD_COLUMN = 4

        // Field values of the COMMAND_ACK fixtures as pymavlink decodes them (see the fixtures README).
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_IN_PROGRESS = 5
        const val IN_PROGRESS_PERCENT = 50
        const val NEGATIVE_RESULT_PARAM2 = -123_456
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        const val POWER_STATUS_ID = 125

        // Field values of the telemetry fixtures as pymavlink decodes them (see the fixtures README); floats
        // are the shortest decimal text of the same float32, printed by tools/fixtures/derive_fixtures.py.
        const val SITL_LAT = -353_633_514
        const val SITL_LON = 1_491_652_411
        const val SITL_AIRSPEED = 0.023872377f
        const val SITL_GROUNDSPEED = 0.023871899f
        const val SITL_HEADING = 359
        const val SITL_ALT_MSL = 587.13f
        const val LARGE_TIME_BOOT_MS = 4_000_000_000L
        const val UINT16_MAX = 65_535
        const val UINT8_MAX = 255
        const val INT8_MIN = -128
        const val HEARTBEAT_ID = 0

        // Field values of the SYS_STATUS and GPS_RAW_INT fixtures as pymavlink decodes them (see the fixtures
        // README). GPS_RAW_INT's raw position differs from GLOBAL_POSITION_INT's filtered one.
        const val SITL_SENSORS_PRESENT = 1_399_979_055L
        const val SITL_SENSORS_ENABLED = 1_382_128_687L
        const val SITL_SENSORS_HEALTH = 1_467_063_343L
        const val BOOT_SENSORS_PRESENT = 1_395_784_719L
        const val BOOT_SENSORS_ENABLED = 1_375_837_199L
        const val BOOT_SENSORS_HEALTH = 1_158_781_963L
        const val SITL_GPS_TIME_USEC = 396_763_000L
        const val SITL_GPS_LAT = -353_633_515
        const val SITL_GPS_LON = 1_491_652_412
        const val SITL_GPS_ALT = 587_150
        const val GPS_FIX_TYPE_NO_GPS = 0
        const val GPS_FIX_TYPE_3D_FIX = 3
        const val GPS_FIX_TYPE_RTK_FIXED = 6
        const val FUZZ_FRAMES = 10_000
        const val MAX_PAYLOAD = 255
        const val MAX_MESSAGE_ID = 0xFF_FFFF
        val FUZZ_IDS = listOf(0, 1, 20, 22, 24, 30, 33, 36, 74, 76, 77, 125, 242, 245, 253)
        const val SCALED_PRESSURE_ID = 29
        const val QUADROTOR = 2
        const val ARDUPILOTMEGA = 3
        const val STANDBY = 3
        const val MAVLINK_VERSION = 3
        const val DISARMED_STABILIZE_BASE_MODE = 81
        const val MAX_UINT32 = 4_294_967_295L
        const val SESSION_UNKNOWN = 3_604L
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
            "SYS_STATUS" to 325,
            "GPS_RAW_INT" to 325,
            "ATTITUDE" to 260,
            "GLOBAL_POSITION_INT" to 130,
            "VFR_HUD" to 260,
            "36/37" to 130,
            "125/6" to 325,
            "76/33" to 11,
            "COMMAND_ACK" to 13,
            "HOME_POSITION" to 1,
            "STATUSTEXT" to 2,
            "PARAM_VALUE" to 3,
        )

        // telemetry/home-position.bin as pymavlink decodes it: ArduPilot sends q as NaN and the approach as 0; the
        // zero high bytes of time_usec are truncated and restored.
        val sitlHomePosition = HomePosition(
            latitude = SITL_LAT,
            longitude = SITL_LON,
            altitude = 587_130,
            x = 0.0111318845f,
            y = -0.009078031f,
            z = 0.02f,
            q1 = Float.NaN,
            q2 = Float.NaN,
            q3 = Float.NaN,
            q4 = Float.NaN,
            approachX = 0f,
            approachY = 0f,
            approachZ = 0f,
            timeUsec = 443_979_837L,
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

        // telemetry/sys-status.bin as pymavlink decodes it: the first one with a battery reading.
        val sitlSysStatus = SysStatus(
            onboardControlSensorsPresent = SITL_SENSORS_PRESENT,
            onboardControlSensorsEnabled = SITL_SENSORS_ENABLED,
            onboardControlSensorsHealth = SITL_SENSORS_HEALTH,
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

        // telemetry/gps-raw-int.bin as pymavlink decodes it: cut inside v_acc, so the rest reads 0.
        val sitlGpsRawInt = GpsRawInt(
            timeUsec = SITL_GPS_TIME_USEC,
            fixType = GPS_FIX_TYPE_RTK_FIXED,
            lat = SITL_GPS_LAT,
            lon = SITL_GPS_LON,
            alt = SITL_GPS_ALT,
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
            yaw = 0,
        )

        // The boot SYS_STATUS: no battery reading yet, so current_battery and battery_remaining are -1
        // ("unknown"); everything but the sensors is 0.
        fun bootSysStatus(voltageBattery: Int) = SysStatus(
            onboardControlSensorsPresent = BOOT_SENSORS_PRESENT,
            onboardControlSensorsEnabled = BOOT_SENSORS_ENABLED,
            onboardControlSensorsHealth = BOOT_SENSORS_HEALTH,
            load = 0,
            voltageBattery = voltageBattery,
            currentBattery = -1,
            batteryRemaining = -1,
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

        fun sitlVfrHud(throttle: Int, climb: Float) = VfrHud(
            airspeed = SITL_AIRSPEED,
            groundspeed = SITL_GROUNDSPEED,
            heading = SITL_HEADING,
            throttle = throttle,
            alt = SITL_ALT_MSL,
            climb = climb,
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
