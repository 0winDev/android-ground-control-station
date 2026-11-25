package com.owindev.gcs.core.domain.message

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-047")
class ShownTextTest {

    @ParameterizedTest
    @ValueSource(ints = [0x00, 0x09, 0x0A, 0x0D, 0x1F])
    fun `GIVEN a C0 control or line break WHEN shown THEN it is a space`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS fix"
    }

    @Test
    fun `GIVEN a DEL WHEN shown THEN it is a space`() {
        val text = "GPS\u007Ffix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS fix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x80, 0x85, 0x9F])
    fun `GIVEN a C1 control WHEN shown THEN it is a space`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS fix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x2028, 0x2029])
    fun `GIVEN a Unicode line or paragraph separator WHEN shown THEN it is a space`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS fix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x202A, 0x202C, 0x202E])
    fun `GIVEN a bidirectional embedding or override WHEN shown THEN it is a replacement character`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS\uFFFDfix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x2066, 0x2069])
    fun `GIVEN a bidirectional isolate WHEN shown THEN it is a replacement character`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS\uFFFDfix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x061C, 0x200E, 0x200F])
    fun `GIVEN a bidirectional mark WHEN shown THEN it is a replacement character`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS\uFFFDfix"
    }

    @ParameterizedTest
    @ValueSource(ints = [0x200B, 0x200D, 0x2060, 0xFEFF])
    fun `GIVEN a zero-width character WHEN shown THEN it is a replacement character`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo "GPS\uFFFDfix"
    }

    @ParameterizedTest
    @ValueSource(
        ints = [
            0x20, 0x7E, 0xA0, 0x061B, 0x061D, 0x200A, 0x2027, 0x202F,
            0x205F, 0x2061, 0x2065, 0x206A, 0xFFFD, 0xFEFE,
        ],
    )
    fun `GIVEN a character next to a replaced range WHEN shown THEN it is kept`(code: Int) {
        val text = "GPS${code.toChar()}fix"

        val result = shownText(text)

        result shouldBeEqualTo text
    }

    @Test
    fun `GIVEN ArduPilot's real pre-arm text WHEN shown THEN it is kept as it came`() {
        val text = "PreArm: GPS 1: Bad fix"

        val result = shownText(text)

        result shouldBeEqualTo text
    }

    @Test
    fun `GIVEN accents and a character outside the BMP WHEN shown THEN they are kept`() {
        val text = "Batería baja \uD83D\uDD0B"

        val result = shownText(text)

        result shouldBeEqualTo text
    }
}
