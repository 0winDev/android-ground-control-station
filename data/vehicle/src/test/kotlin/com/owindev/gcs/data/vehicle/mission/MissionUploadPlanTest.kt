package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The protocol's rules are checked here, outside the codec, which keeps any uint8 and uint32 (#148). */
@Tag("REQ-056")
class MissionUploadPlanTest {

    @Test
    fun `GIVEN home and two items WHEN planned THEN the count names all three with opaque_id 0`() {
        val items = listOf(home(), item(seq = 1), item(seq = 2))

        val result = MissionUploadPlan.of(items)?.countMessage()

        result shouldBeEqualTo MissionCount(
            targetSystem = TARGET_SYSTEM,
            targetComponent = TARGET_COMPONENT,
            count = 3,
            missionType = 0,
            opaqueId = 0,
        )
    }

    @Test
    fun `GIVEN no item WHEN planned THEN there is no plan`() {
        val items = emptyList<MissionItemInt>()

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN only home WHEN planned THEN there is no plan`() {
        val items = listOf(home())

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN home and one item WHEN planned THEN the count is 2`() {
        val items = listOf(home(), item(seq = 1))

        val result = MissionUploadPlan.of(items)?.count

        result shouldBeEqualTo 2
    }

    @Test
    fun `GIVEN a seq that is not its index WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 2))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN current 2 WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 1).copy(current = 2))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN autocontinue 2 WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 1).copy(autocontinue = 2))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN current and autocontinue 0 and 1 WHEN planned THEN every item is kept`() {
        val items = listOf(
            home(),
            item(seq = 1).copy(current = 0, autocontinue = 0),
            item(seq = 2).copy(current = 1, autocontinue = 1),
        )

        val result = MissionUploadPlan.of(items)?.items

        result shouldBeEqualTo items
    }

    @Test
    fun `GIVEN an item of the fence plan WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 1).copy(missionType = MAV_MISSION_TYPE_FENCE))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN an item for another system WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 1).copy(targetSystem = TARGET_SYSTEM + 1))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN an item for another component WHEN planned THEN there is no plan`() {
        val items = listOf(home(), item(seq = 1).copy(targetComponent = TARGET_COMPONENT + 1))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN item 0 a take off WHEN planned THEN there is no plan`() {
        val items = listOf(home().copy(command = MAV_CMD_NAV_TAKEOFF_ID), item(seq = 1))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN item 0 above home WHEN planned THEN there is no plan`() {
        val items = listOf(home().copy(frame = MAV_FRAME_GLOBAL_RELATIVE_ALT), item(seq = 1))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN item 0 not current WHEN planned THEN there is no plan`() {
        val items = listOf(home().copy(current = 0), item(seq = 1))

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN 65535 items WHEN planned THEN the count holds them`() {
        val items = listOf(home()) + (1 until MAX_UINT16).map { item(seq = it) }

        val result = MissionUploadPlan.of(items)?.count

        result shouldBeEqualTo MAX_UINT16
    }

    @Test
    fun `GIVEN 65536 items WHEN planned THEN there is no plan`() {
        val items = listOf(home()) + (1..MAX_UINT16).map { item(seq = it) }

        val result = MissionUploadPlan.of(items)

        result.shouldBeNull()
    }

    private companion object {
        const val TARGET_SYSTEM = 1
        const val TARGET_COMPONENT = 1
        const val MAV_MISSION_TYPE_FENCE = 1
        const val MAV_CMD_NAV_TAKEOFF_ID = 22
        const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        const val MAX_UINT16 = 65_535

        fun home() = item(seq = 0).copy(frame = 0, command = 16, current = 1)

        fun item(seq: Int) = MissionItemInt(
            targetSystem = TARGET_SYSTEM,
            targetComponent = TARGET_COMPONENT,
            seq = seq,
            frame = MAV_FRAME_GLOBAL_RELATIVE_ALT,
            command = 16,
            current = 0,
            autocontinue = 1,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = 0f,
            x = -353_633_515,
            y = 1_491_652_412,
            z = 10f,
            missionType = 0,
        )
    }
}
