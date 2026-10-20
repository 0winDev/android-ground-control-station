package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class TlogWriterTest {

    private lateinit var writer: TlogWriter

    @BeforeEach
    fun setUp() {
        writer = TlogWriter
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN QGroundControl's first record WHEN writing it THEN the bytes are the record as stored`() {
        val stored = readFixture(QGROUNDCONTROL).copyOfRange(0, TIMESTAMP_LENGTH + QGROUNDCONTROL_FIRST_FRAME)
        val frame = stored.copyOfRange(TIMESTAMP_LENGTH, stored.size)

        val result = writer.record(QGROUNDCONTROL_FIRST_TIMESTAMP, frame)

        result.asList() shouldBeEqualTo stored.asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a timestamp with its low two bits set WHEN writing a record THEN they are cleared, big-endian`() {
        val frame = readFixture(DISARMED_STABILIZE)

        val result = writer.record(LOW_BITS_SET, frame)

        result.asList() shouldBeEqualTo (bytes(0x00, 0x06, 0x5D, 0xB1, 0x2E, 0x8A, 0x7F, 0x6C) + frame).asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the SITL session WHEN reading and writing it again THEN the bytes are the same but the low bits`() {
        val session = readFixture(SESSION)
        val records = TlogReader().read(session)

        val result = records.map { writer.record(it.timestampMicros, it.frame) }.reduce(ByteArray::plus)

        result.asList() shouldBeEqualTo lowBitsCleared(session, sessionFrameSizes()).asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN two frames in one buffer WHEN writing a record THEN it is rejected`() {
        val datagram = readFixture(DISARMED_STABILIZE) + readFixture(DISARMED_LOITER)

        val result = invoking { writer.record(QGROUNDCONTROL_FIRST_TIMESTAMP, datagram) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN bytes that are not a whole frame WHEN writing a record THEN it is rejected`() {
        val cut = readFixture(DISARMED_STABILIZE).copyOfRange(0, CUT_FRAME_LENGTH)

        val result = invoking { writer.record(QGROUNDCONTROL_FIRST_TIMESTAMP, cut) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN no bytes at all WHEN writing a record THEN it is rejected`() {
        val empty = ByteArray(0)

        val result = invoking { writer.record(QGROUNDCONTROL_FIRST_TIMESTAMP, empty) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a negative timestamp WHEN writing a record THEN it is rejected`() {
        val frame = readFixture(DISARMED_STABILIZE)

        val result = invoking { writer.record(-1, frame) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN an unsigned real frame WHEN taking it for the log THEN it is the same array, unchanged`() {
        val frame = readFixture(DISARMED_STABILIZE)

        val result = writer.loggable(frame, MavlinkMessages)

        result.written() shouldBe frame
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN an unsigned frame with an unknown message ID WHEN taking it for the log THEN it is kept unchanged`() {
        val frame = readFixture(SCALED_PRESSURE)

        val result = writer.loggable(frame, MavlinkMessages)

        result.written() shouldBe frame
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed HEARTBEAT WHEN taking it for the log THEN it is the real frame it was signed from`() {
        val signed = readFixture(HEARTBEAT_SIGNED)

        val result = writer.loggable(signed, MavlinkMessages)

        result.written().asList() shouldBeEqualTo readFixture(DISARMED_STABILIZE).asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed ATTITUDE with 0xFD inside WHEN taking it for the log THEN it is the real frame unsigned`() {
        val signed = readFixture(ATTITUDE_SIGNED)

        val result = writer.loggable(signed, MavlinkMessages)

        result.written().asList() shouldBeEqualTo readFixture(ATTITUDE_FD_IN_PAYLOAD).asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed truncated VFR_HUD WHEN taking it for the log THEN it keeps its LEN without the signature`() {
        val signed = readFixture(VFR_HUD_SIGNED)

        val result = writer.loggable(signed, MavlinkMessages)

        result.written().asList() shouldBeEqualTo readFixture(VFR_HUD).asList()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed frame with an unknown message ID WHEN taking it for the log THEN it is left out`() {
        val signed = readFixture(SCALED_PRESSURE_SIGNED)

        val result = writer.loggable(signed, MavlinkMessages)

        result shouldBeEqualTo LogFrame.SignedUnknownMessage
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a SETUP_SIGNING WHEN taking it for the log THEN it is left out`() {
        val setupSigning = readFixture(SETUP_SIGNING)

        val result = writer.loggable(setupSigning, MavlinkMessages)

        result shouldBeEqualTo LogFrame.SetupSigning
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed SETUP_SIGNING whose CRC_EXTRA is known WHEN taking it for the log THEN it is left out`() {
        val setupSigning = readFixture(SETUP_SIGNING_SIGNED)
        val lookup = CrcExtraLookup { msgId ->
            if (msgId == SETUP_SIGNING_ID) SETUP_SIGNING_CRC_EXTRA else MavlinkMessages.crcExtraFor(msgId)
        }

        val result = writer.loggable(setupSigning, lookup)

        result shouldBeEqualTo LogFrame.SetupSigning
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a real MAVLink 1 frame with bit 0x01 set at index 2 WHEN taking it for the log THEN it is left out`() {
        val frame = TlogReader().read(readFixture(SESSION)).map { it.frame }.first(::mavlink1WithLowSeqBitSet)

        val result = writer.loggable(frame, MavlinkMessages)

        result shouldBeEqualTo LogFrame.Mavlink1
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed frame with a known ID plus a high MSGID byte WHEN taking it for the log THEN it is left out`() {
        val signed = readFixture(HEARTBEAT_SIGNED).copyOf().also { it[MSGID_HIGH_INDEX] = 0x01 }

        val result = writer.loggable(signed, MavlinkMessages)

        result shouldBeEqualTo LogFrame.SignedUnknownMessage
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN two frames in one buffer WHEN taking them for the log THEN they are rejected`() {
        val datagram = readFixture(HEARTBEAT_SIGNED) + readFixture(DISARMED_LOITER)

        val result = invoking { writer.loggable(datagram, MavlinkMessages) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN bytes that are not a whole frame WHEN taking them for the log THEN they are rejected`() {
        val cut = readFixture(HEARTBEAT_SIGNED).copyOfRange(0, CUT_FRAME_LENGTH)

        val result = invoking { writer.loggable(cut, MavlinkMessages) }

        result shouldThrow IllegalArgumentException::class
    }

    private companion object {
        const val QGROUNDCONTROL = "tlog/qgroundcontrol.tlog"
        const val HEARTBEAT_SIGNED = "derived/heartbeat-signed.bin"
        const val ATTITUDE_SIGNED = "derived/attitude-signed.bin"
        const val ATTITUDE_FD_IN_PAYLOAD = "unknown/attitude-fd-in-payload.bin"
        const val VFR_HUD_SIGNED = "derived/vfr-hud-signed.bin"
        const val VFR_HUD = "telemetry/vfr-hud.bin"
        const val SCALED_PRESSURE = "unknown/scaled-pressure.bin"
        const val SCALED_PRESSURE_SIGNED = "derived/scaled-pressure-signed.bin"
        const val SETUP_SIGNING = "derived/setup-signing.bin"
        const val SETUP_SIGNING_SIGNED = "derived/setup-signing-signed.bin"

        // common.xml and pymavlink 2.4.50's common dialect.
        const val SETUP_SIGNING_ID = 256
        const val SETUP_SIGNING_CRC_EXTRA = 71
        const val SESSION = "heartbeat/session.tlog"
        const val SESSION_RECORDS = "tlog/session.records.csv"
        const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
        const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
        const val TIMESTAMP_LENGTH = 8
        const val CUT_FRAME_LENGTH = 15

        // tlog/qgroundcontrol.records.csv, line 1.
        const val QGROUNDCONTROL_FIRST_TIMESTAMP = 1_792_091_306_747_000L
        const val QGROUNDCONTROL_FIRST_FRAME = 36

        // 2026-10-13 04:23:51.687023 UTC, its low two bits both set: 00 06 5D B1 2E 8A 7F 6F, written as ...7F 6C.
        const val LOW_BITS_SET = 0x0006_5DB1_2E8A_7F6FL
        const val LOW_BITS_MASK = 0x03

        // MAVLink 2 MSGID is bytes 7..9, little-endian: 0x01 there makes HEARTBEAT (0) message ID 65536.
        const val MSGID_HIGH_INDEX = 9
        const val MAVLINK1_START = 0xFE.toByte()
        const val SEQ_INDEX_MAVLINK1 = 2

        fun mavlink1WithLowSeqBitSet(frame: ByteArray): Boolean =
            frame[0] == MAVLINK1_START && frame[SEQ_INDEX_MAVLINK1].toInt() and 0x01 != 0

        fun LogFrame.written(): ByteArray = (this as LogFrame.Write).bytes

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        fun sessionFrameSizes(): List<Int> = String(readFixture(SESSION_RECORDS), Charsets.UTF_8)
            .lines()
            .filter { it.isNotEmpty() }
            .map { it.substringAfter(",").toInt() }

        fun lowBitsCleared(tlog: ByteArray, frameSizes: List<Int>): ByteArray {
            val cleared = tlog.copyOf()
            var offset = 0
            for (size in frameSizes) {
                val lastTimestampByte = offset + TIMESTAMP_LENGTH - 1
                cleared[lastTimestampByte] = (cleared[lastTimestampByte].toInt() and LOW_BITS_MASK.inv()).toByte()
                offset += TIMESTAMP_LENGTH + size
            }
            return cleared
        }
    }
}
