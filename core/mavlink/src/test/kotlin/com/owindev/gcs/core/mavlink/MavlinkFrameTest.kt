package com.owindev.gcs.core.mavlink

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Frames hold arrays, so equality must compare their contents, not their references. */
class MavlinkFrameTest {

    private lateinit var frame: MavlinkFrame

    @BeforeEach
    fun setUp() {
        frame = frame()
    }

    @Test
    fun `GIVEN a frame with the same contents in other arrays WHEN comparing THEN it is equal with the same hash`() {
        val copy = frame()

        val result = listOf(frame == copy, frame.hashCode() == copy.hashCode())

        result shouldBeEqualTo listOf(true, true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentFrames")
    fun `GIVEN a frame that differs in one field WHEN comparing THEN it is not equal`(other: MavlinkFrame) {
        val sameFrame = frame

        val result = sameFrame == other

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN an object that is not a frame WHEN comparing THEN it is not equal`() {
        val other: Any = PAYLOAD

        val result = frame.equals(other)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a signed frame WHEN printing it THEN it shows the header and the length but not the bytes`() {
        val signed = frame(signature = SIGNATURE)

        val result = signed.toString()

        result shouldBeEqualTo "MavlinkFrame(seq=59, sys=1, comp=1, msgid=0, incompat=1, compat=0, len=3, signed=true)"
        result shouldNotBeEqualTo frame.toString()
    }

    private companion object {
        val PAYLOAD = byteArrayOf(0x02, 0x03, 0x51)
        val SIGNATURE = ByteArray(13) { it.toByte() }

        // One default per header field, so each test names only the field it changes.
        @Suppress("LongParameterList")
        fun frame(
            sequence: Int = 59,
            systemId: Int = 1,
            componentId: Int = 1,
            messageId: Int = 0,
            compatFlags: Int = 0,
            payload: ByteArray = PAYLOAD.copyOf(),
            signature: ByteArray? = null,
        ) = MavlinkFrame(
            sequence = sequence,
            systemId = systemId,
            componentId = componentId,
            messageId = messageId,
            incompatFlags = if (signature == null) 0 else 1,
            compatFlags = compatFlags,
            payload = payload,
            signature = signature,
        )

        @JvmStatic
        fun differentFrames(): List<MavlinkFrame> = listOf(
            frame(sequence = 60),
            frame(systemId = 2),
            frame(componentId = 2),
            frame(messageId = 1),
            frame(compatFlags = 0x80),
            frame(payload = byteArrayOf(0x02, 0x03)),
            frame(signature = SIGNATURE),
        )
    }
}
