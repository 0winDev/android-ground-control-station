package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.ExtendedSysState
import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * EXTENDED_SYS_STATE decoder tests from real ArduCopter 4.7.2 SITL frames and frames re-packed by pymavlink 2.4.50,
 * run through the parser with the codec's message table. Expected values come from pymavlink (see
 * `src/test/resources/README.md`): ArduCopter always reports `vtol_state` 3 (MAV_VTOL_STATE_MC).
 */
@Tag("REQ-052")
class MavlinkDecoderExtendedSysStateTest {

    private lateinit var decoder: MavlinkDecoder

    @BeforeEach
    fun setUp() {
        decoder = MavlinkDecoder
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "landed/on-ground.bin, 1",
        "landed/takeoff.bin, 3",
        "landed/in-air.bin, 2",
        "landed/landing.bin, 4",
    )
    fun `GIVEN a real SITL EXTENDED_SYS_STATE WHEN decoding THEN it has the states pymavlink decodes`(
        fixture: String,
        landedState: Int,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo ExtendedSysState(vtolState = MAV_VTOL_STATE_MC, landedState = landedState)
    }

    @Tag("REQ-013")
    @Test
    fun `GIVEN an UNDEFINED landed state truncated to one byte WHEN decoding THEN it reads as zero`() {
        val frame = parsedFrame("derived/extended-sys-state-undefined.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo ExtendedSysState(vtolState = MAV_VTOL_STATE_MC, landedState = 0)
    }

    @Test
    fun `GIVEN a landed state past MAV_LANDED_STATE WHEN decoding THEN it is kept as sent`() {
        val frame = parsedFrame("derived/extended-sys-state-landed-5.bin")

        val result = decoder.decode(frame)

        result shouldBeEqualTo ExtendedSysState(vtolState = MAV_VTOL_STATE_MC, landedState = 5)
    }

    @Test
    fun `GIVEN every real EXTENDED_SYS_STATE of QGroundControl's SITL log WHEN decoding THEN each is in the air`() {
        val parsed = MavlinkParser(MavlinkMessages).parse(readFixture("tlog/qgroundcontrol.frames.bin"))

        val result = parsed.map { decoder.decode(it) }.filterIsInstance<ExtendedSysState>()

        result shouldBeEqualTo
            List(QGROUNDCONTROL_FRAMES) { ExtendedSysState(MAV_VTOL_STATE_MC, MAV_LANDED_STATE_IN_AIR) }
    }

    private fun parsedFrame(fixture: String): MavlinkFrame =
        MavlinkParser(MavlinkMessages).parse(readFixture(fixture)).single()

    private companion object {
        const val MAV_VTOL_STATE_MC = 3
        const val MAV_LANDED_STATE_IN_AIR = 2
        const val QGROUNDCONTROL_FRAMES = 10
    }
}
