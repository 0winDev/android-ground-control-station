package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.testing.readFixture
import kotlin.random.Random
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The .tlog reader against real logs saved from ArduPilot SITL by QGroundControl and Mission Planner, the
 * record-by-record excerpt of a Mission Planner session (`heartbeat/session.tlog`), and damaged logs derived from it
 * (see `src/test/resources/README.md`). Expected records are what pymavlink reads (`tlog/<name>.records.csv` and the
 * frames concatenated), or follow from how a damaged log was made; never from the reader.
 */
class TlogReaderTest {

    private lateinit var reader: TlogReader

    @BeforeEach
    fun setUp() {
        reader = TlogReader()
    }

    @Tag("REQ-036")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "tlog/qgroundcontrol.tlog, tlog/qgroundcontrol.records.csv, tlog/qgroundcontrol.frames.bin",
        "tlog/mission-planner.tlog, tlog/mission-planner.records.csv, tlog/mission-planner.frames.bin",
        "heartbeat/session.tlog, tlog/session.records.csv, derived/session-stream.bin",
    )
    fun `GIVEN a real SITL tlog in random chunks WHEN reading THEN every record is what pymavlink reads`(
        tlog: String,
        records: String,
        frames: String,
    ) {
        val chunks = chunks(readFixture(tlog), Random(SEED))

        val result = chunks.flatMap { reader.read(it) } + reader.finish()

        result shouldBeEqualTo expectedRecords(records, frames)
        reader.stats shouldBeEqualTo TlogStats()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN QGroundControl's tlog fed one byte at a time WHEN reading THEN every record is what pymavlink reads`() {
        val bytes = readFixture(QGROUNDCONTROL)

        val result = bytes.flatMap { reader.read(byteArrayOf(it)) } + reader.finish()

        result shouldBeEqualTo expectedRecords(QGROUNDCONTROL_RECORDS, QGROUNDCONTROL_FRAMES)
        reader.stats shouldBeEqualTo TlogStats()
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN garbage between two records WHEN reading THEN every record is recovered and the garbage counted`() {
        val log = readFixture(GARBAGE_BETWEEN_RECORDS)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo firstSessionRecords
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = GARBAGE_LENGTH)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN garbage before the first record WHEN reading THEN the records after it come, garbage counted`() {
        val log = readFixture(GARBAGE_BEFORE_FIRST_RECORD)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo firstSessionRecords
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = GARBAGE_LENGTH)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a false start before the first record WHEN reading THEN it costs one record, never the rest`() {
        val log = readFixture(FALSE_START_BEFORE_FIRST_RECORD)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo listOf(falseFirstRecord) + firstSessionRecords.drop(1)
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = restOfFirstRecord)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a timestamp without a frame between records WHEN reading THEN every record is recovered`() {
        val log = readFixture(TIMESTAMP_WITHOUT_FRAME)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo firstSessionRecords
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = TIMESTAMP_LENGTH)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a lone timestamp before one holding a start marker WHEN reading THEN no false record comes out`() {
        val log = readFixture(FALSE_START_AFTER_LONE_TIMESTAMP)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo sessionRecords.subList(FALSE_START_FIRST, FALSE_START_FIRST + FALSE_START_RECORDS)
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = TIMESTAMP_LENGTH)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a last record cut inside its frame WHEN reading to the end THEN the whole ones come out, it counted`() {
        val log = readFixture(TRUNCATED_LAST_RECORD)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo firstSessionRecords.dropLast(1)
        reader.stats shouldBeEqualTo TlogStats(truncatedRecords = 1)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a lone timestamp at the end WHEN reading to the end THEN every record comes out and it is counted`() {
        val log = readFixture(TIMESTAMP_AT_END)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo firstSessionRecords
        reader.stats shouldBeEqualTo TlogStats(truncatedRecords = 1)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a false record start hiding real records at the end WHEN reading to the end THEN they are rescued`() {
        val log = readFixture(FALSE_START_AT_END)

        val result = reader.read(log) + reader.finish()

        result shouldBeEqualTo sessionRecords.take(DAMAGED_RECORDS + HIDDEN_RECORDS)
        reader.stats shouldBeEqualTo TlogStats(skippedBytes = TIMESTAMP_LENGTH + FALSE_HEADER_LENGTH)
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN an empty log WHEN reading to the end THEN there are no records and nothing is counted`() {
        val log = ByteArray(0)

        val result = reader.read(log) + reader.finish()

        result.shouldBeEmpty()
        reader.stats shouldBeEqualTo TlogStats()
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN a megabyte of random bytes in random chunks WHEN reading to the end THEN nothing is thrown`() {
        val noise = chunks(Random(SEED).nextBytes(FUZZ_LENGTH), Random(SEED))

        val result = invoking {
            noise.forEach { reader.read(it) }
            reader.finish()
        }

        result shouldNotThrow AnyException
    }

    private fun chunks(stream: ByteArray, random: Random): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < stream.size) {
            val end = minOf(stream.size, start + random.nextInt(1, MAX_CHUNK + 1))
            chunks += stream.copyOfRange(start, end)
            start = end
        }
        return chunks
    }

    private companion object {
        const val QGROUNDCONTROL = "tlog/qgroundcontrol.tlog"
        const val QGROUNDCONTROL_RECORDS = "tlog/qgroundcontrol.records.csv"
        const val QGROUNDCONTROL_FRAMES = "tlog/qgroundcontrol.frames.bin"
        const val SESSION_RECORDS = "tlog/session.records.csv"
        const val SESSION_FRAMES = "derived/session-stream.bin"
        const val GARBAGE_BETWEEN_RECORDS = "derived/tlog-garbage-between-records.bin"
        const val GARBAGE_BEFORE_FIRST_RECORD = "derived/tlog-garbage-before-first-record.bin"
        const val FALSE_START_BEFORE_FIRST_RECORD = "derived/tlog-false-start-before-first-record.bin"
        const val TIMESTAMP_WITHOUT_FRAME = "derived/tlog-timestamp-without-frame.bin"
        const val FALSE_START_AFTER_LONE_TIMESTAMP = "derived/tlog-false-start-after-lone-timestamp.bin"
        const val TRUNCATED_LAST_RECORD = "derived/tlog-truncated-last-record.bin"
        const val TIMESTAMP_AT_END = "derived/tlog-timestamp-at-end.bin"
        const val FALSE_START_AT_END = "derived/tlog-false-start-at-end.bin"

        // How the damaged logs were made (tools/fixtures/tlog_fixtures.py, which prints them).
        const val DAMAGED_RECORDS = 10
        const val GARBAGE_LENGTH = 7L
        const val TIMESTAMP_LENGTH = 8L
        const val FALSE_START_FIRST = 418
        const val FALSE_START_RECORDS = 23
        const val HIDDEN_RECORDS = 3
        const val FALSE_HEADER_LENGTH = 10L
        const val SEED = 68
        const val MAX_CHUNK = 4_096
        const val FUZZ_LENGTH = 1_048_576

        val sessionRecords = expectedRecords(SESSION_RECORDS, SESSION_FRAMES)
        val firstSessionRecords = sessionRecords.take(DAMAGED_RECORDS)

        // Before any anchor, 8 bytes of 0x55 and 0xFE are taken for a MAVLink 1 record. Its LEN is 0, the top byte of
        // record 0's timestamp, so its 8-byte frame is 0xFE and the first 7 bytes of that timestamp.
        const val GARBAGE_TIMESTAMP = 0x5555_5555_5555_5555L
        const val MAVLINK1_START: Byte = 0xFE.toByte()
        const val FALSE_FRAME_TIMESTAMP_BYTES = 7
        val falseFirstRecord = TlogRecord(
            GARBAGE_TIMESTAMP,
            byteArrayOf(MAVLINK1_START) +
                bigEndian(firstSessionRecords[0].timestampMicros).copyOf(FALSE_FRAME_TIMESTAMP_BYTES),
        )

        // What the false record leaves of record 0, skipped up to record 1: its last timestamp byte and its frame.
        val restOfFirstRecord = TIMESTAMP_LENGTH - FALSE_FRAME_TIMESTAMP_BYTES + firstSessionRecords[0].frame.size
    }
}

private fun bigEndian(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { index ->
    (value shr (Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS).toByte()
}

/** The records pymavlink reads: one `timestamp,length` line per record, and their frames concatenated. */
private fun expectedRecords(records: String, frames: String): List<TlogRecord> {
    val bytes = readFixture(frames)
    var offset = 0
    return String(readFixture(records), Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map { line ->
        val (timestamp, length) = line.split(",")
        val frame = bytes.copyOfRange(offset, offset + length.toInt())
        offset += length.toInt()
        TlogRecord(timestamp.toLong(), frame)
    }
}
