package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotThrow
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Checksum tests from real ArduPilot SITL frames (see `src/test/resources/README.md`). The expected
 * checksum is the frame's own checksum bytes, which pymavlink 2.4.50 validated; CRC_EXTRA values come
 * from pymavlink's bundled `minimal.xml` (HEARTBEAT) and `common.xml` (the others).
 */
class MavlinkCrcTest {

    private lateinit var crc: MavlinkCrc

    @BeforeEach
    fun setUp() {
        crc = MavlinkCrc
    }

    @Tag("REQ-011")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "heartbeat/disarmed-stabilize.bin, 50",
        "heartbeat/disarmed-guided.bin, 50",
        "heartbeat/disarmed-loiter.bin, 50",
        "heartbeat/armed-guided.bin, 50",
        "truncated/sys_status.bin, 124",
        "truncated/power_status.bin, 203",
        "truncated/servo_output_raw.bin, 222",
    )
    fun `GIVEN a real SITL frame WHEN computing its checksum THEN it matches the checksum bytes`(
        fixture: String,
        crcExtra: Int,
    ) {
        val frame = readFixture(fixture)

        val result = crc.compute(frame, CRC_OFFSET, crcLength(frame), crcExtra)

        result shouldBeEqualTo wireChecksum(frame)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a real frame with one payload bit flipped WHEN computing the checksum THEN it does not match`() {
        val frame = readFixture(HEARTBEAT_FIXTURE)
        frame[FIRST_PAYLOAD_BYTE] = (frame[FIRST_PAYLOAD_BYTE].toInt() xor LOWEST_BIT).toByte()

        val result = crc.compute(frame, CRC_OFFSET, crcLength(frame), HEARTBEAT_CRC_EXTRA)

        result shouldNotBeEqualTo wireChecksum(frame)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a real frame and a wrong CRC_EXTRA WHEN computing the checksum THEN it does not match`() {
        val frame = readFixture(HEARTBEAT_FIXTURE)
        val wrongCrcExtra = HEARTBEAT_CRC_EXTRA + 1

        val result = crc.compute(frame, CRC_OFFSET, crcLength(frame), wrongCrcExtra)

        result shouldNotBeEqualTo wireChecksum(frame)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a real frame WHEN the start marker is included in the checksum THEN it does not match`() {
        val frame = readFixture(HEARTBEAT_FIXTURE)
        val withStartMarker = crcLength(frame) + 1

        val result = crc.compute(frame, 0, withStartMarker, HEARTBEAT_CRC_EXTRA)

        result shouldNotBeEqualTo wireChecksum(frame)
    }

    @ParameterizedTest(name = "crcExtra {0}")
    @CsvSource(
        "-1",
        "256",
        "306",
    )
    fun `GIVEN a CRC_EXTRA outside one byte WHEN computing the checksum THEN it throws IllegalArgumentException`(
        crcExtra: Int,
    ) {
        val frame = readFixture(HEARTBEAT_FIXTURE)

        val result = invoking { crc.compute(frame, CRC_OFFSET, crcLength(frame), crcExtra) }

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN every possible CRC_EXTRA byte WHEN computing the checksum THEN each result fits in 16 bits`() {
        val frame = readFixture(HEARTBEAT_FIXTURE)

        val result = (0..MAX_BYTE).map { crc.compute(frame, CRC_OFFSET, crcLength(frame), it) }

        result.forEach { it shouldBeInRange 0..MAX_CHECKSUM }
    }

    @Test
    fun `GIVEN an empty range at the end of the array WHEN computing the checksum THEN it is accepted`() {
        val frame = readFixture(HEARTBEAT_FIXTURE)

        val result = invoking { crc.compute(frame, frame.size, 0, HEARTBEAT_CRC_EXTRA) }

        result shouldNotThrow AnyException
    }

    @ParameterizedTest(name = "offset {0}, length {1}")
    @CsvSource(
        "-1, 4",
        "0, -1",
        "0, 22",
        "21, 1",
        "1, 2147483647",
    )
    fun `GIVEN a range outside the array WHEN computing the checksum THEN it throws IllegalArgumentException`(
        offset: Int,
        length: Int,
    ) {
        val frame = readFixture(HEARTBEAT_FIXTURE)

        val result = invoking { crc.compute(frame, offset, length, HEARTBEAT_CRC_EXTRA) }

        result shouldThrow IllegalArgumentException::class
    }

    private fun crcLength(frame: ByteArray): Int = HEADER_LENGTH_WITHOUT_MARKER + payloadLength(frame)

    private fun payloadLength(frame: ByteArray): Int = frame[LEN_INDEX].toInt() and BYTE_MASK

    private fun wireChecksum(frame: ByteArray): Int {
        val low = frame[frame.size - CHECKSUM_LENGTH].toInt() and BYTE_MASK
        val high = frame[frame.size - 1].toInt() and BYTE_MASK
        return low or (high shl Byte.SIZE_BITS)
    }

    private companion object {
        const val HEARTBEAT_FIXTURE = "heartbeat/disarmed-stabilize.bin"
        const val HEARTBEAT_CRC_EXTRA = 50
        const val CRC_OFFSET = 1
        const val LEN_INDEX = 1
        const val HEADER_LENGTH_WITHOUT_MARKER = 9
        const val FIRST_PAYLOAD_BYTE = 10
        const val CHECKSUM_LENGTH = 2
        const val LOWEST_BIT = 0x01
        const val BYTE_MASK = 0xFF
        const val MAX_BYTE = 0xFF
        const val MAX_CHECKSUM = 0xFFFF
    }
}
