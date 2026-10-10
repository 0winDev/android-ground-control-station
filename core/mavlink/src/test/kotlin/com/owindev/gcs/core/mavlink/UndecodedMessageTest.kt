package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** An undecoded message holds an array, so equality must compare its contents, not its reference. */
class UndecodedMessageTest {

    private lateinit var message: Undecoded

    @BeforeEach
    fun setUp() {
        message = Undecoded(POWER_STATUS_ID, PAYLOAD.copyOf())
    }

    @Test
    fun `GIVEN a message with the same contents in another array WHEN comparing THEN it is equal with the same hash`() {
        val copy = Undecoded(POWER_STATUS_ID, PAYLOAD.copyOf())

        val result = listOf(message == copy, message.hashCode() == copy.hashCode())

        result shouldBeEqualTo listOf(true, true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentMessages")
    fun `GIVEN a message that differs WHEN comparing THEN it is not equal`(other: Any) {
        val sameMessage = message

        val result = sameMessage == other

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN an undecoded message WHEN printing it THEN it shows the message ID and the length`() {
        val printed = message

        val result = printed.toString()

        result shouldBeEqualTo "Undecoded(msgid=125, len=6)"
    }

    private companion object {
        const val POWER_STATUS_ID = 125
        val PAYLOAD = byteArrayOf(0x88.toByte(), 0x13, 0x00, 0x00, 0x00, 0x00)

        @JvmStatic
        fun differentMessages(): List<Any> = listOf(
            Undecoded(POWER_STATUS_ID + 1, PAYLOAD.copyOf()),
            Undecoded(POWER_STATUS_ID, PAYLOAD.copyOf(PAYLOAD.size - 1)),
            PAYLOAD,
        )
    }
}
