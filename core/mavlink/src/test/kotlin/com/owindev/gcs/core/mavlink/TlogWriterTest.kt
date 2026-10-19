package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The .tlog writer against real records (see `src/test/resources/README.md`): QGroundControl's timestamps are whole
 * milliseconds, so their low two bits are already 0 and its records come back byte for byte; Mission Planner's
 * session leaves them set, so its records come back with only those two bits cleared, as pymavlink writes them.
 */
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

    private companion object {
        const val QGROUNDCONTROL = "tlog/qgroundcontrol.tlog"
        const val SESSION = "heartbeat/session.tlog"
        const val SESSION_RECORDS = "tlog/session.records.csv"
        const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
        const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
        const val TIMESTAMP_LENGTH = 8
        const val CUT_FRAME_LENGTH = 15

        // The first line of tlog/qgroundcontrol.records.csv (pymavlink).
        const val QGROUNDCONTROL_FIRST_TIMESTAMP = 1_792_091_306_747_000L
        const val QGROUNDCONTROL_FIRST_FRAME = 36

        // 2026-10-13 04:23:51.687023 UTC, its low two bits both set: 00 06 5D B1 2E 8A 7F 6F, written as ...7F 6C.
        const val LOW_BITS_SET = 0x0006_5DB1_2E8A_7F6FL
        const val LOW_BITS_MASK = 0x03

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        /** The frame length of each record of the session, as pymavlink reads them (`tlog/session.records.csv`). */
        fun sessionFrameSizes(): List<Int> = String(readFixture(SESSION_RECORDS), Charsets.UTF_8)
            .lines()
            .filter { it.isNotEmpty() }
            .map { it.substringAfter(",").toInt() }

        /** [tlog] with the two low bits of each record's timestamp cleared; records found by their frames' sizes. */
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
