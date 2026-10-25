package com.owindev.gcs.core.mavlink

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The codec's message table against pymavlink 2.4.50's bundled definitions (`minimal.xml` for HEARTBEAT,
 * `standard.xml` for GLOBAL_POSITION_INT, `common.xml` for the rest), as listed in `src/test/resources/README.md`.
 */
class MavlinkMessagesTest {

    private lateinit var messages: MavlinkMessages

    @BeforeEach
    fun setUp() {
        messages = MavlinkMessages
    }

    @Tag("REQ-011")
    @Tag("REQ-013")
    @Tag("REQ-022")
    @Tag("REQ-024")
    @Tag("REQ-025")
    @Tag("REQ-032")
    @Tag("REQ-040")
    @Tag("REQ-041")
    @ParameterizedTest(name = "msgid {0}")
    @CsvSource(
        "0, 50, 9",
        "1, 124, 43",
        "24, 24, 52",
        "30, 39, 28",
        "33, 104, 28",
        "36, 222, 37",
        "74, 20, 20",
        "125, 203, 6",
        "76, 152, 33",
        "77, 143, 10",
        "242, 104, 60",
        "253, 83, 54",
        "20, 214, 20",
        "22, 220, 25",
    )
    fun `GIVEN a supported message WHEN looking it up THEN it has the CRC_EXTRA and full length of its definition`(
        msgId: Int,
        crcExtra: Int,
        fullLength: Int,
    ) {
        val supported = msgId

        val result = messages.crcExtraFor(supported) to messages.fullLengthOf(supported)

        result shouldBeEqualTo (crcExtra to fullLength)
    }

    @Tag("REQ-011")
    @Test
    fun `GIVEN a message outside the table WHEN looking it up THEN it is not supported`() {
        val scaledPressure = SCALED_PRESSURE_ID

        val result = messages.crcExtraFor(scaledPressure) to messages.fullLengthOf(scaledPressure)

        result shouldBeEqualTo (null to null)
    }

    private companion object {
        const val SCALED_PRESSURE_ID = 29
    }
}
