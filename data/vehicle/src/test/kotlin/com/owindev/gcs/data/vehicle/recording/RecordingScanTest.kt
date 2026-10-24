package com.owindev.gcs.data.vehicle.recording

import com.owindev.gcs.core.testing.readFixture
import kotlin.random.Random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Expected stamps from pymavlink 2.4.50: the first and last of the log's 345 frames whose IDs the codec knows. */
@Tag("REQ-038")
class RecordingScanTest {

    @Test
    fun `GIVEN QGroundControl's log WHEN scanning it THEN spans its first to its last valid frame`() {
        val log = readFixture(QGROUNDCONTROL_LOG)

        val result = RecordingScan.of(log.inputStream())

        result shouldBeEqualTo RecordingScan(FIRST_MICROS, LAST_MICROS)
    }

    @Test
    fun `GIVEN garbage before QGroundControl's log WHEN scanning it THEN the garbage adds no frame`() {
        val log = Random(SEED).nextBytes(GARBAGE_BYTES) + readFixture(QGROUNDCONTROL_LOG)

        val result = RecordingScan.of(log.inputStream())

        result shouldBeEqualTo RecordingScan(FIRST_MICROS, LAST_MICROS)
    }

    @Test
    fun `GIVEN random bytes WHEN scanning them THEN no valid frame`() {
        val bytes = Random(SEED).nextBytes(RANDOM_BYTES)

        val result = RecordingScan.of(bytes.inputStream())

        result.hasValidFrame shouldBeEqualTo false
    }

    @Test
    fun `GIVEN an empty file WHEN scanning it THEN no valid frame`() {
        val bytes = ByteArray(0)

        val result = RecordingScan.of(bytes.inputStream())

        result shouldBeEqualTo RecordingScan(firstMicros = null, lastMicros = null)
    }

    @Test
    fun `GIVEN a text file WHEN scanning it THEN no valid frame`() {
        val bytes = "Flight notes: hover at 20 m, then RTL.\n".repeat(TEXT_LINES).toByteArray()

        val result = RecordingScan.of(bytes.inputStream())

        result.hasValidFrame shouldBeEqualTo false
    }

    private companion object {
        const val QGROUNDCONTROL_LOG = "replay/qgroundcontrol.tlog"
        const val FIRST_MICROS = 1_792_091_306_747_000L
        const val LAST_MICROS = 1_792_091_316_682_000L
        const val SEED = 74
        const val GARBAGE_BYTES = 1_000
        const val RANDOM_BYTES = 1024 * 1024
        const val TEXT_LINES = 1_000
    }
}
