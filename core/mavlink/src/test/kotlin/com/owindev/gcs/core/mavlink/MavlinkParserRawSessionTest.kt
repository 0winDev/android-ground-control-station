package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.RawFrame.UnknownMessage
import com.owindev.gcs.core.mavlink.RawFrame.Valid
import com.owindev.gcs.core.testing.readFixture
import kotlin.random.Random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class MavlinkParserRawSessionTest {

    private lateinit var parser: MavlinkParser

    @BeforeEach
    fun setUp() {
        parser = MavlinkParser(MavlinkMessages)
    }

    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN the SITL session in random datagrams WHEN parsing raw THEN every MAVLink 2 frame comes out exactly`() {
        val datagrams = chunks(readFixture(SESSION_STREAM), Random(SEED))

        val result = datagrams.flatMap { parser.parseRaw(it) }

        result.size shouldBeEqualTo SESSION_MAVLINK2_FRAMES
        result.flatMap { it.bytes.asList() } shouldBeEqualTo readFixture(SESSION_MAVLINK2_STREAM).asList()
        result.filterIsInstance<UnknownMessage>().size.toLong() shouldBeEqualTo SESSION_UNKNOWN
        parser.stats shouldBeEqualTo ParserStats(
            unknownMessageId = SESSION_UNKNOWN,
            skippedBytes = SESSION_MAVLINK1_BYTES,
        )
    }

    // parse() as the reference is the parser's own; MavlinkParserTest ties it to pymavlink.
    @Tag("REQ-010")
    @Tag("REQ-036")
    @Test
    fun `GIVEN the SITL session in random datagrams WHEN parsing raw THEN the valid frames and stats are parse's`() {
        val datagrams = chunks(readFixture(SESSION_STREAM), Random(SEED))
        val reference = MavlinkParser(MavlinkMessages)
        val expected = datagrams.flatMap { reference.parse(it) }

        val result = datagrams.flatMap { parser.parseRaw(it) }

        result.filterIsInstance<Valid>().map { it.frame } shouldBeEqualTo expected
        parser.stats shouldBeEqualTo reference.stats
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
        const val SESSION_STREAM = "derived/session-stream.bin"
        const val SESSION_MAVLINK2_STREAM = "derived/session-mavlink2-stream.bin"

        // Counts printed by tools/fixtures/derive_fixtures.py (pymavlink).
        const val SESSION_MAVLINK2_FRAMES = 5_522
        const val SESSION_UNKNOWN = 3_604L
        const val SESSION_MAVLINK1_BYTES = 82L
        const val SEED = 8
        const val MAX_DATAGRAM = 300
    }
}
