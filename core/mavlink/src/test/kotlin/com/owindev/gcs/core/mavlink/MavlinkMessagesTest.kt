package com.owindev.gcs.core.mavlink

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The v0.1 message table against pymavlink 2.4.50's bundled definitions (`minimal.xml` for HEARTBEAT,
 * `common.xml` for the rest), as listed in `src/test/resources/README.md`.
 */
class MavlinkMessagesTest {

    private lateinit var messages: MavlinkMessages

    @BeforeEach
    fun setUp() {
        messages = MavlinkMessages
    }

    @Tag("REQ-011")
    @Tag("REQ-013")
    @ParameterizedTest(name = "msgid {0}")
    @CsvSource(
        "0, 50, 9",
        "1, 124, 43",
        "36, 222, 37",
        "125, 203, 6",
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
        val attitude = ATTITUDE_ID

        val result = messages.crcExtraFor(attitude) to messages.fullLengthOf(attitude)

        result shouldBeEqualTo (null to null)
    }

    private companion object {
        const val ATTITUDE_ID = 30
    }
}
