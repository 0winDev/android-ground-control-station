package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `param_id` is `char[16]` (`common.xml`): a request is built only with a name that fits it as ASCII. */
class ParamRequestReadTest {

    private lateinit var request: ParamRequestRead

    @BeforeEach
    fun setUp() {
        request = ParamRequestRead(targetSystem = 1, targetComponent = 1, paramId = "BATT_LOW_VOLT")
    }

    @Tag("REQ-041")
    @Test
    fun `GIVEN a 16-character name WHEN building the request THEN it keeps the name`() {
        val sixteenCharacters = "INS_GYR1_CALTEMP"

        val result = request.copy(paramId = sixteenCharacters)

        result.paramId shouldBeEqualTo sixteenCharacters
    }

    @Tag("REQ-041")
    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["", "INS_GYR1_CALTEMPS", "BATT_LÓW_VOLT", "BATT\u0000LOW"])
    fun `GIVEN an empty, too long, non-ASCII or NUL name WHEN building the request THEN it throws`(paramId: String) {
        val invalid = paramId

        val result = invoking { request.copy(paramId = invalid) }

        result shouldThrow IllegalArgumentException::class
    }
}
