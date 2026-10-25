package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue
import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * PARAM_VALUE decoder tests from real ArduPilot SITL frames and frames re-packed by pymavlink 2.4.50, run through the
 * parser with the codec's message table. Expected values come from pymavlink (see `src/test/resources/README.md`).
 */
class MavlinkDecoderParamTest {

    private lateinit var decoder: MavlinkDecoder

    @BeforeEach
    fun setUp() {
        decoder = MavlinkDecoder
    }

    @Tag("REQ-041")
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        "param/stat-runtime.bin, STAT_RUNTIME, 2008, 6",
        "param/mot-thst-hover.bin, MOT_THST_HOVER, 0.39, 9",
        "param/ins-gyr1-caltemp.bin, INS_GYR1_CALTEMP, 25.018353, 9",
    )
    fun `GIVEN a real SITL PARAM_VALUE WHEN decoding THEN it has the fields pymavlink decodes and the float as sent`(
        fixture: String,
        paramId: String,
        value: Float,
        type: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = paramId,
            value = value,
            type = type,
            count = SITL_PARAM_COUNT,
            index = UINT16_MAX,
        )
    }

    @Tag("REQ-041")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "derived/session-stream.bin, param/session.tsv",
        "tlog/mission-planner.frames.bin, param/mission-planner.tsv",
    )
    fun `GIVEN every real PARAM_VALUE of a SITL log WHEN decoding THEN each is what pymavlink decodes`(
        frames: String,
        expected: String,
    ) {
        val parsed = MavlinkParser(MavlinkMessages).parse(readFixture(frames))

        val result = parsed.map { decoder.decode(it) }.filterIsInstance<ParamValue>()

        result shouldBeEqualTo paramValuesOf(expected)
    }

    @Tag("REQ-041")
    @Test
    fun `GIVEN ArduCopter's BATT_LOW_VOLT WHEN decoding THEN it has its name, volts and REAL32 type`() {
        val frame = parsedFrame(PARAM_VALUE_BATT_LOW_VOLT)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = "BATT_LOW_VOLT",
            value = 10.5f,
            type = MAV_PARAM_TYPE_REAL32,
            count = SITL_PARAM_COUNT,
            index = UINT16_MAX,
        )
    }

    @Tag("REQ-041")
    @Test
    fun `GIVEN a PARAM_VALUE with a distinct byte in every field WHEN decoding THEN each field lands in its place`() {
        val frame = parsedFrame(PARAM_VALUE_FIELDS)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = "ABCDEFGHIJKLMNOP",
            value = -123.456f,
            type = MAV_PARAM_TYPE_UINT32,
            count = 0x1234,
            index = 0x5678,
        )
    }

    @Tag("REQ-041")
    @Test
    fun `GIVEN a PARAM_VALUE with id bytes after a NUL WHEN decoding THEN the id ends at the NUL`() {
        val frame = parsedFrame(PARAM_VALUE_NUL_INSIDE)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = "AB",
            value = SITL_THST_HOVER,
            type = MAV_PARAM_TYPE_REAL32,
            count = SITL_PARAM_COUNT,
            index = UINT16_MAX,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-041")
    @Test
    fun `GIVEN a PARAM_VALUE truncated after its id WHEN decoding THEN the padding and type read as zero`() {
        val frame = parsedFrame(PARAM_VALUE_TYPE_0)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = "BATT_LOW_VOLT",
            value = SITL_THST_HOVER,
            type = 0,
            count = SITL_PARAM_COUNT,
            index = UINT16_MAX,
        )
    }

    @Tag("REQ-013")
    @Tag("REQ-041")
    @Test
    fun `GIVEN a PARAM_VALUE truncated before its id WHEN decoding THEN the id is empty and the counters are kept`() {
        val frame = parsedFrame(PARAM_VALUE_EMPTY_ID)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ParamValue(
            paramId = "",
            value = SITL_THST_HOVER,
            type = 0,
            count = SITL_PARAM_COUNT,
            index = UINT16_MAX,
        )
    }

    private fun parsedFrame(fixture: String): MavlinkFrame =
        MavlinkParser(MavlinkMessages).parse(readFixture(fixture)).single()

    private fun paramValuesOf(table: String): List<ParamValue> =
        readFixture(table).decodeToString().lines().drop(1).filter { it.isNotEmpty() }.map { line ->
            val columns = line.split(TAB)
            ParamValue(
                paramId = columns[PARAM_ID_COLUMN],
                value = columns[PARAM_VALUE_COLUMN].toFloat(),
                type = columns[PARAM_TYPE_COLUMN].toInt(),
                count = columns[PARAM_COUNT_COLUMN].toInt(),
                index = columns[PARAM_INDEX_COLUMN].toInt(),
            )
        }

    private companion object {
        const val PARAM_VALUE_BATT_LOW_VOLT = "derived/param-value-batt-low-volt.bin"
        const val PARAM_VALUE_FIELDS = "derived/param-value-fields.bin"
        const val PARAM_VALUE_NUL_INSIDE = "derived/param-value-nul-inside.bin"
        const val PARAM_VALUE_TYPE_0 = "derived/param-value-type-0.bin"
        const val PARAM_VALUE_EMPTY_ID = "derived/param-value-empty-id.bin"
        const val PARAM_ID_COLUMN = 0
        const val PARAM_VALUE_COLUMN = 1
        const val PARAM_TYPE_COLUMN = 2
        const val PARAM_COUNT_COLUMN = 3
        const val PARAM_INDEX_COLUMN = 4

        // Field values of the PARAM_VALUE fixtures as pymavlink decodes them (see the fixtures README).
        const val SITL_PARAM_COUNT = 1370
        const val SITL_THST_HOVER = 0.39f
        const val MAV_PARAM_TYPE_UINT32 = 5
        const val MAV_PARAM_TYPE_REAL32 = 9
        const val UINT16_MAX = 65_535
        const val TAB = "\t"
    }
}
