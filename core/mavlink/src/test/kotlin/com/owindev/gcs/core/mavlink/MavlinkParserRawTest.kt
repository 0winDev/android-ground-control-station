package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.RawFrame.UnknownMessage
import com.owindev.gcs.core.mavlink.RawFrame.Valid
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
import org.junit.jupiter.params.provider.ValueSource

/**
 * The exact bytes the parser hands out for the flight log (ADR 0006), from real ArduPilot SITL frames and frames
 * derived from them by pymavlink (see `src/test/resources/README.md`). Expected bytes are the fixtures themselves,
 * never the parser's output. The lookup knows only HEARTBEAT (CRC_EXTRA 50, from `minimal.xml`).
 *
 * The fuzz test compares `parseRaw()` with `parse()` on the same bytes. That reference is the parser's own, but
 * `MavlinkParserTest` ties `parse()` to pymavlink, and the test also checks an invariant that needs no reference:
 * every unknown-ID frame handed out is one counted.
 */
class MavlinkParserRawTest {

    private lateinit var parser: MavlinkParser

    @BeforeEach
    fun setUp() {
        parser = MavlinkParser(heartbeatOnly)
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a real HEARTBEAT WHEN parsing raw THEN it is valid with its exact bytes`() {
        val datagram = readFixture(DISARMED_STABILIZE)

        val result = parser.parseRaw(datagram)

        result shouldBeEqualTo listOf(Valid(disarmedStabilizeFrame, readFixture(DISARMED_STABILIZE)))
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a frame signed by pymavlink WHEN parsing raw THEN its bytes include the signature`() {
        val datagram = readFixture(SIGNED)

        val result = parser.parseRaw(datagram)

        result shouldBeEqualTo listOf(Valid(signedFrame, readFixture(SIGNED)))
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a real frame with an unknown message ID WHEN parsing raw THEN it is handed out whole and counted`() {
        val datagram = readFixture(POWER_STATUS)

        val result = parser.parseRaw(datagram)

        result shouldBeEqualTo listOf(UnknownMessage(POWER_STATUS_ID, readFixture(POWER_STATUS)))
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN an unknown frame with 0xFD in its payload WHEN parsing raw THEN it is whole and the next follows`() {
        val datagram = readFixture(ATTITUDE_WITH_FD) + readFixture(DISARMED_STABILIZE)

        val result = parser.parseRaw(datagram)

        result shouldBeEqualTo listOf(
            UnknownMessage(ATTITUDE_ID, readFixture(ATTITUDE_WITH_FD)),
            Valid(disarmedStabilizeFrame, readFixture(DISARMED_STABILIZE)),
        )
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a signed frame with an unknown message ID WHEN parsing raw THEN it is handed out with its signature`() {
        val datagram = readFixture(SIGNED_ATTITUDE) + readFixture(DISARMED_STABILIZE)

        val result = parser.parseRaw(datagram)

        result shouldBeEqualTo listOf(
            UnknownMessage(ATTITUDE_ID, readFixture(SIGNED_ATTITUDE)),
            Valid(disarmedStabilizeFrame, readFixture(DISARMED_STABILIZE)),
        )
        parser.stats shouldBeEqualTo ParserStats(unknownMessageId = 1)
    }

    @Tag("REQ-011")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a known message with a bad checksum WHEN parsing raw THEN it is dropped and counted`() {
        val corrupted = readFixture(DISARMED_STABILIZE)
        corrupted[FIRST_PAYLOAD_BYTE] = (corrupted[FIRST_PAYLOAD_BYTE].toInt() xor LOWEST_BIT).toByte()

        val result = parser.parseRaw(corrupted)

        result.shouldBeEmpty()
        parser.stats shouldBeEqualTo ParserStats(badChecksum = 1, skippedBytes = HEARTBEAT_FRAME_SIZE)
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [CORRUPTED_LEN_RAISED, CORRUPTED_LEN_LOWERED])
    fun `GIVEN a corrupted LEN before real frames WHEN parsing raw THEN every real frame after it comes out`(
        fixture: String,
    ) {
        val following = FOLLOWING_FIXTURES.map { readFixture(it) }
        val stream = following.fold(readFixture(fixture), ByteArray::plus)

        val result = chunks(stream, Random(SEED)).flatMap { parser.parseRaw(it) }

        result.map { it.bytes.asList() } shouldBeEqualTo following.map { it.asList() }
        parser.stats shouldBeEqualTo ParserStats(badChecksum = 1, skippedBytes = HEARTBEAT_FRAME_SIZE)
    }

    @Tag("REQ-012")
    @Tag("REQ-036")
    @Test
    fun `GIVEN a megabyte of random bytes WHEN parsing raw THEN the valid frames and stats are parse's`() {
        val noise = chunks(Random(SEED).nextBytes(FUZZ_LENGTH), Random(SEED))
        val reference = MavlinkParser(heartbeatOnly)
        val expected = noise.flatMap { reference.parse(it) }

        val result = noise.flatMap { parser.parseRaw(it) }

        result.filterIsInstance<Valid>().map { it.frame } shouldBeEqualTo expected
        parser.stats shouldBeEqualTo reference.stats
        result.filterIsInstance<UnknownMessage>().size.toLong() shouldBeEqualTo parser.stats.unknownMessageId
    }

    @Tag("REQ-012")
    @Test
    fun `GIVEN a megabyte of random bytes in random datagrams WHEN parsing raw THEN nothing is thrown`() {
        val noise = chunks(Random(SEED).nextBytes(FUZZ_LENGTH), Random(SEED))

        val result = invoking { noise.forEach { parser.parseRaw(it) } }

        result shouldNotThrow AnyException
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

    private companion object {
        const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
        const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
        const val SIGNED = "derived/heartbeat-signed.bin"
        const val SIGNED_ATTITUDE = "derived/attitude-signed.bin"
        const val POWER_STATUS = "truncated/power_status.bin"
        const val ATTITUDE_WITH_FD = "unknown/attitude-fd-in-payload.bin"
        const val CORRUPTED_LEN_RAISED = "derived/heartbeat-corrupted-len-raised.bin"
        const val CORRUPTED_LEN_LOWERED = "derived/heartbeat-corrupted-len-lowered.bin"

        // As the fixtures script: enough real frames after the corrupted one to fill a maximum frame (lesson #9).
        val FOLLOWING_FIXTURES = List(8) { listOf(DISARMED_STABILIZE, DISARMED_LOITER) }.flatten()
        const val HEARTBEAT_ID = 0
        const val HEARTBEAT_CRC_EXTRA = 50
        const val ATTITUDE_ID = 30
        const val POWER_STATUS_ID = 125
        const val HEARTBEAT_FRAME_SIZE = 21L
        const val FIRST_PAYLOAD_BYTE = 10
        const val LOWEST_BIT = 0x01
        const val SEED = 8
        const val MAX_DATAGRAM = 300
        const val FUZZ_LENGTH = 1_048_576
        val heartbeatOnly = CrcExtraLookup { if (it == HEARTBEAT_ID) HEARTBEAT_CRC_EXTRA else null }
        val disarmedStabilizePayload = bytes(0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x51, 0x03, 0x03)
        val disarmedStabilizeFrame = heartbeatFrame(incompatFlags = 0, signature = null)
        val signedFrame = heartbeatFrame(
            incompatFlags = 0x01,
            signature = bytes(0x00, 0x78, 0x56, 0x34, 0x12, 0x00, 0x00, 0x5a, 0xa5, 0x3a, 0xfc, 0x01, 0xb6),
        )

        fun heartbeatFrame(incompatFlags: Int, signature: ByteArray?) = MavlinkFrame(
            sequence = 59,
            systemId = 1,
            componentId = 1,
            messageId = HEARTBEAT_ID,
            incompatFlags = incompatFlags,
            compatFlags = 0,
            payload = disarmedStabilizePayload,
            signature = signature,
        )

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}
