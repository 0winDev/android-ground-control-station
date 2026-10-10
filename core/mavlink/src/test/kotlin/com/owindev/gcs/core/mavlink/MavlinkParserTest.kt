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

/**
 * Parser tests from real ArduPilot SITL frames and from frames derived from them by pymavlink (see
 * `src/test/resources/README.md`). Expected header fields and counts come from pymavlink, never from
 * the parser. The lookup knows only HEARTBEAT (CRC_EXTRA 50, from `minimal.xml`).
 */
class MavlinkParserTest {

    private lateinit var parser: MavlinkParser

    @BeforeEach
    fun setUp() {
        parser = MavlinkParser(heartbeatOnly)
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN a datagram with one real HEARTBEAT WHEN parsing THEN it yields that frame`() {
        val datagram = readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN several real frames in one datagram WHEN parsing THEN it yields all of them in order`() {
        val datagram = HEARTBEAT_FIXTURES.map { readFixture(it) }.reduce(ByteArray::plus)

        val result = parser.parse(datagram)

        result.map { it.sequence } shouldBeEqualTo HEARTBEAT_SEQUENCES
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN a real frame split across two datagrams WHEN parsing both THEN the frame comes out of the second`() {
        val frame = readFixture(DISARMED_STABILIZE)
        val datagrams = listOf(frame.copyOfRange(0, SPLIT_POINT), frame.copyOfRange(SPLIT_POINT, frame.size))

        val result = datagrams.map { parser.parse(it) }

        result shouldBeEqualTo listOf(emptyList(), listOf(disarmedStabilizeFrame))
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN a real frame fed one byte per datagram WHEN parsing THEN it yields the frame once`() {
        val frame = readFixture(DISARMED_STABILIZE)

        val result = frame.map { parser.parse(byteArrayOf(it)) }.flatten()

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
    }

    @Tag("REQ-010")
    @Tag("REQ-011")
    @Test
    fun `GIVEN the SITL session in random datagrams WHEN parsing THEN it yields the HEARTBEATs pymavlink finds`() {
        val stream = readFixture(SESSION_STREAM)

        val result = chunks(stream, Random(SEED)).flatMap { parser.parse(it) }

        result.size shouldBeEqualTo SESSION_HEARTBEATS
        parser.stats shouldBeEqualTo ParserStats(
            unknownMessageId = SESSION_UNKNOWN_MESSAGES,
            skippedBytes = SESSION_MAVLINK1_BYTES,
        )
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN false long frame starts between real frames WHEN parsing THEN every real frame is recovered`() {
        val stream = framesWithFalseStarts()

        val result = chunks(stream, Random(SEED)).flatMap { parser.parse(it) }

        result.map { it.sequence } shouldBeEqualTo List(NOISY_ROUNDS) { HEARTBEAT_SEQUENCES }.flatten()
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN a frame signed by pymavlink WHEN parsing THEN it yields the frame with its 13-byte signature`() {
        val datagram = readFixture(SIGNED)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(signedFrame)
    }

    @Tag("REQ-010")
    @Test
    fun `GIVEN a signed frame followed by a real frame WHEN parsing THEN the signature is consumed with its frame`() {
        val datagram = readFixture(SIGNED) + readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(signedFrame, disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats()
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a signed frame with an unknown message ID WHEN parsing THEN it is skipped with its signature`() {
        val datagram = readFixture(SIGNED_ATTITUDE) + readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-010")
    @Tag("REQ-011")
    @Test
    fun `GIVEN a bad checksum right before a real frame WHEN parsing THEN the rescan recovers the real frame`() {
        val corrupted = readFixture(DISARMED_STABILIZE)
        corrupted[FIRST_PAYLOAD_BYTE] = (corrupted[FIRST_PAYLOAD_BYTE].toInt() xor LOWEST_BIT).toByte()

        val result = parser.parse(corrupted + readFixture(DISARMED_STABILIZE))

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats(badChecksum = 1, skippedBytes = HEARTBEAT_FRAME_SIZE)
    }

    @Tag("REQ-010")
    @Tag("REQ-014")
    @Test
    fun `GIVEN an unsupported flag right before a real frame WHEN parsing THEN the rescan recovers the real frame`() {
        val datagram = readFixture(UNKNOWN_INCOMPAT) + readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats(
            unsupportedIncompatFlags = 1,
            skippedBytes = HEARTBEAT_FRAME_SIZE,
        )
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a real frame with one payload bit flipped WHEN parsing THEN it is dropped as a bad checksum`() {
        val datagram = readFixture(DISARMED_STABILIZE)
        datagram[FIRST_PAYLOAD_BYTE] = (datagram[FIRST_PAYLOAD_BYTE].toInt() xor LOWEST_BIT).toByte()

        val result = parser.parse(datagram)

        result.shouldBeEmpty()
        parser.stats shouldBeEqualTo ParserStats(badChecksum = 1, skippedBytes = HEARTBEAT_FRAME_SIZE)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a real frame with an unknown message ID WHEN parsing THEN it is dropped and counted`() {
        val datagram = readFixture(POWER_STATUS)

        val result = parser.parse(datagram)

        result.shouldBeEmpty()
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN an unknown real frame with 0xFD in its payload WHEN parsing THEN it is skipped whole by its LEN`() {
        val datagram = readFixture(ATTITUDE_WITH_FD) + readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-014")
    @Test
    fun `GIVEN a frame with an unsupported incompatibility flag WHEN parsing THEN it is dropped and counted`() {
        val datagram = readFixture(UNKNOWN_INCOMPAT)

        val result = parser.parse(datagram)

        result.shouldBeEmpty()
        parser.stats shouldBeEqualTo ParserStats(
            unsupportedIncompatFlags = 1,
            skippedBytes = HEARTBEAT_FRAME_SIZE,
        )
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN a frame with LEN 0 before a real frame WHEN parsing THEN it is dropped and the real frame recovered`() {
        val datagram = readFixture(EMPTY_PAYLOAD) + readFixture(DISARMED_STABILIZE)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(disarmedStabilizeFrame)
        parser.stats shouldBeEqualTo ParserStats(emptyPayload = 1, skippedBytes = EMPTY_PAYLOAD_FRAME_SIZE)
    }

    @Tag("REQ-014")
    @Test
    fun `GIVEN a frame with an unknown compatibility flag WHEN parsing THEN the frame is accepted`() {
        val datagram = readFixture(UNKNOWN_COMPAT)

        val result = parser.parse(datagram)

        result shouldBeEqualTo listOf(unknownCompatFrame)
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN bytes without a start marker WHEN parsing THEN every byte is counted as skipped`() {
        val datagram = ByteArray(NOISE_LENGTH) { NOT_A_START_MARKER }

        val result = parser.parse(datagram)

        result.shouldBeEmpty()
        parser.stats shouldBeEqualTo ParserStats(skippedBytes = NOISE_LENGTH.toLong())
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN an empty datagram WHEN parsing THEN it yields nothing`() {
        val datagram = ByteArray(0)

        val result = parser.parse(datagram)

        result.shouldBeEmpty()
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN a megabyte of random bytes in random datagrams WHEN parsing THEN nothing is thrown`() {
        val noise = chunks(Random(SEED).nextBytes(FUZZ_LENGTH), Random(SEED))

        val result = invoking { noise.forEach { parser.parse(it) } }

        result shouldNotThrow AnyException
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN random bytes followed by a real frame WHEN parsing THEN the frame is still found`() {
        val noise = chunks(Random(SEED).nextBytes(FUZZ_LENGTH), Random(SEED))
        val frame = readFixture(DISARMED_STABILIZE)

        val result = (noise + listOf(streamGoesOn, frame)).flatMap { parser.parse(it) }.last()

        result shouldBeEqualTo disarmedStabilizeFrame
    }

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

    private fun framesWithFalseStarts(): ByteArray {
        val frames = HEARTBEAT_FIXTURES.map { readFixture(it) }
        val falseStart = falseHeartbeatHeader + ByteArray(FALSE_START_TAIL) { NOT_A_START_MARKER }
        val noisy = List(NOISY_ROUNDS) { frames.flatMap { listOf(falseStart, it) } }.flatten().reduce(ByteArray::plus)
        return noisy + streamGoesOn
    }

    private companion object {
        const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
        val HEARTBEAT_FIXTURES = listOf(
            DISARMED_STABILIZE,
            "heartbeat/disarmed-guided.bin",
            "heartbeat/disarmed-loiter.bin",
            "heartbeat/armed-guided.bin",
        )
        val HEARTBEAT_SEQUENCES = listOf(59, 18, 173, 122)
        const val SIGNED = "derived/heartbeat-signed.bin"
        const val SIGNED_ATTITUDE = "derived/attitude-signed.bin"
        const val UNKNOWN_INCOMPAT = "derived/heartbeat-unknown-incompat.bin"
        const val UNKNOWN_COMPAT = "derived/heartbeat-unknown-compat.bin"
        const val EMPTY_PAYLOAD = "derived/heartbeat-empty-payload.bin"
        const val SESSION_STREAM = "derived/session-stream.bin"
        const val POWER_STATUS = "truncated/power_status.bin"
        const val ATTITUDE_WITH_FD = "unknown/attitude-fd-in-payload.bin"
        const val HEARTBEAT_ID = 0
        const val HEARTBEAT_CRC_EXTRA = 50
        const val SESSION_HEARTBEATS = 133
        const val SESSION_UNKNOWN_MESSAGES = 5_389L
        const val SESSION_MAVLINK1_BYTES = 82L
        const val HEARTBEAT_FRAME_SIZE = 21L
        const val EMPTY_PAYLOAD_FRAME_SIZE = 12L
        const val SPLIT_POINT = 7
        const val FIRST_PAYLOAD_BYTE = 10
        const val LOWEST_BIT = 0x01
        const val SEED = 8
        const val MAX_DATAGRAM = 300
        const val FUZZ_LENGTH = 1_048_576
        const val NOISE_LENGTH = 64
        const val NOT_A_START_MARKER: Byte = 0x55
        const val FALSE_START_TAIL = 20
        const val MAX_FRAME_LENGTH = 280
        const val NOISY_ROUNDS = 25
        val heartbeatOnly = CrcExtraLookup { if (it == HEARTBEAT_ID) HEARTBEAT_CRC_EXTRA else null }
        val disarmedStabilizePayload = bytes(0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x51, 0x03, 0x03)
        val disarmedStabilizeFrame = heartbeatFrame(incompatFlags = 0, compatFlags = 0, signature = null)
        val signedFrame = heartbeatFrame(
            incompatFlags = 0x01,
            compatFlags = 0,
            signature = bytes(0x00, 0x78, 0x56, 0x34, 0x12, 0x00, 0x00, 0x5a, 0xa5, 0x3a, 0xfc, 0x01, 0xb6),
        )
        val unknownCompatFrame = heartbeatFrame(incompatFlags = 0, compatFlags = 0x80, signature = null)

        // A plausible HEARTBEAT header (no flags, known msgid) claiming LEN 255: the parser must wait for
        // the whole claimed frame, which swallows the real frames behind it until the checksum fails.
        // The stream goes on after the noise: a false start (up to one maximum frame long) is resolved, and
        // nothing that follows it can be swallowed by a false start inside the noise.
        val streamGoesOn = ByteArray(MAX_FRAME_LENGTH) { NOT_A_START_MARKER }

        val falseHeartbeatHeader = bytes(0xFD, 0xFF, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00)

        fun heartbeatFrame(incompatFlags: Int, compatFlags: Int, signature: ByteArray?) = MavlinkFrame(
            sequence = 59,
            systemId = 1,
            componentId = 1,
            messageId = HEARTBEAT_ID,
            incompatFlags = incompatFlags,
            compatFlags = compatFlags,
            payload = disarmedStabilizePayload,
            signature = signature,
        )

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}
