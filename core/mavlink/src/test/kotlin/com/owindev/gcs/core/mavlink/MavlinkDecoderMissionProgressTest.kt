package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCurrent
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemReached
import com.owindev.gcs.core.mavlink.MavlinkMessage.Undecoded
import com.owindev.gcs.core.testing.readFixture
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Mission progress decoder tests from a route flown in AUTO in SITL (ArduCopter 4.8.0-dev) and frames re-packed by
 * pymavlink, run through the parser with the codec's message table. Expected values come from pymavlink with a
 * dialect generated from `common.xml` on master (see `src/test/resources/README.md`, "Mission progress").
 */
@Tag("REQ-054")
class MavlinkDecoderMissionProgressTest {

    private lateinit var decoder: MavlinkDecoder

    @BeforeEach
    fun setUp() {
        decoder = MavlinkDecoder
    }

    @Tag("REQ-013")
    @ParameterizedTest(name = "{0}")
    @MethodSource("currents")
    fun `GIVEN a MISSION_CURRENT WHEN decoding THEN it has the fields pymavlink decodes`(
        fixture: String,
        expected: MissionCurrent,
    ) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo expected
    }

    @Tag("REQ-013")
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "mission/reached-takeoff.bin, 1",
        "mission/reached-rtl.bin, 4",
        "derived/mission-item-reached-seq-high.bin, 65534",
    )
    fun `GIVEN a MISSION_ITEM_REACHED WHEN decoding THEN it has the seq pymavlink decodes`(fixture: String, seq: Int) {
        val frame = parsedFrame(fixture)

        val result = decoder.decode(frame)

        result shouldBeEqualTo MissionItemReached(seq)
    }

    @Test
    fun `GIVEN the whole captured AUTO flight WHEN decoding THEN every message is the type pymavlink reads`() {
        val frames = TlogReader().read(readFixture("mission/progress-session.tlog")).map { it.frame }
        val parser = MavlinkParser(MavlinkMessages)

        val result = frames.flatMap { parser.parse(it) }.map { decoder.decode(it).kind() }.groupingBy { it }.eachCount()

        result.toSortedMap() shouldBeEqualTo SESSION_COUNTS
    }

    @Test
    fun `GIVEN the captured AUTO flight WHEN decoding THEN the items reached and the current items come in order`() {
        val frames = TlogReader().read(readFixture("mission/progress-session.tlog")).map { it.frame }
        val parser = MavlinkParser(MavlinkMessages)

        val result = frames.flatMap { parser.parse(it) }.map { decoder.decode(it) }

        result.filterIsInstance<MissionItemReached>().map { it.seq } shouldBeEqualTo REACHED_IN_ORDER
        result.filterIsInstance<MissionCurrent>().map { it.seq }.distinctConsecutive() shouldBeEqualTo CURRENT_IN_ORDER
    }

    private fun parsedFrame(fixture: String): MavlinkFrame =
        MavlinkParser(MavlinkMessages).parse(readFixture(fixture)).single()

    private fun MavlinkMessage.kind(): String = when (this) {
        is Undecoded -> "Undecoded $messageId"
        else -> this::class.simpleName.orEmpty()
    }

    private fun List<Int>.distinctConsecutive(): List<Int> =
        filterIndexed { index, seq -> index == 0 || this[index - 1] != seq }

    private companion object {

        // As pymavlink decodes them (see the fixtures README).
        @JvmStatic
        fun currents(): List<Arguments> = listOf(
            arguments(
                "mission/current-not-started.bin",
                MissionCurrent(1, TOTAL, MISSION_STATE_NOT_STARTED, NOT_IN_MISSION_MODE, 0, 0, 0),
            ),
            arguments(
                "mission/current-active.bin",
                MissionCurrent(2, TOTAL, MISSION_STATE_ACTIVE, IN_MISSION_MODE, 0, 0, 0),
            ),
            arguments(
                "mission/current-complete.bin",
                MissionCurrent(0, TOTAL, MISSION_STATE_COMPLETE, IN_MISSION_MODE, 0, 0, 0),
            ),
            arguments(
                "derived/mission-current-full.bin",
                MissionCurrent(0x0102, 0x0304, MISSION_STATE_PAUSED, SUSPENDED, MISSION_ID, FENCE_ID, RALLY_POINTS_ID),
            ),
            arguments("derived/mission-current-seq-only.bin", MissionCurrent(2, 0, 0, 0, 0, 0, 0)),
            arguments(
                "derived/mission-current-no-mission.bin",
                MissionCurrent(0, TOTAL_NO_MISSION, MISSION_STATE_NO_MISSION, NOT_IN_MISSION_MODE, 0, 0, 0),
            ),
            arguments(
                "derived/mission-current-state-past-enum.bin",
                MissionCurrent(2, TOTAL, STATE_PAST_ENUM, IN_MISSION_MODE, 0, 0, 0),
            ),
        )

        const val TOTAL = 4
        const val TOTAL_NO_MISSION = 65_535
        const val MISSION_STATE_NO_MISSION = 1
        const val MISSION_STATE_NOT_STARTED = 2
        const val MISSION_STATE_ACTIVE = 3
        const val MISSION_STATE_PAUSED = 4
        const val MISSION_STATE_COMPLETE = 5
        const val STATE_PAST_ENUM = 6

        // ArduPilot's mission_mode: 1 in AUTO, 0 otherwise; common.xml's 2 (suspended) only in the derived frame.
        const val NOT_IN_MISSION_MODE = 0
        const val IN_MISSION_MODE = 1
        const val SUSPENDED = 2

        // Each at or above 2^31: a signed read of the uint32 would come out negative.
        const val MISSION_ID = 0x8765_4321L
        const val FENCE_ID = 0x9876_5432L
        const val RALLY_POINTS_ID = 0xA987_6543L

        // mission/progress-session.txt: 124 records.
        val SESSION_COUNTS = sortedMapOf(
            "MissionCurrent" to 120,
            "MissionItemReached" to 4,
        )

        // The route: 1 take off, 2 and 3 waypoints, 4 RTL; 0 after it completes.
        val REACHED_IN_ORDER = listOf(1, 2, 3, 4)
        val CURRENT_IN_ORDER = listOf(1, 2, 3, 4, 0)
    }
}
