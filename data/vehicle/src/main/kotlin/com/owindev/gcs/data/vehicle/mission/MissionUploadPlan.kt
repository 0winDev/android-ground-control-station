package com.owindev.gcs.data.vehicle.mission

import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionCount
import com.owindev.gcs.core.mavlink.MavlinkMessage.MissionItemInt

/** `MAV_MISSION_TYPE_MISSION` (`common.xml`). */
internal const val MAV_MISSION_TYPE_MISSION = 0

/** `MAV_CMD_NAV_WAYPOINT` (`common.xml`). */
internal const val MAV_CMD_NAV_WAYPOINT = 16

/** `MAV_FRAME_GLOBAL` (`common.xml`): altitude above mean sea level. */
internal const val MAV_FRAME_GLOBAL = 0

/** MISSION_COUNT's `count` and every `seq` are uint16. */
private const val MAX_COUNT = 65_535

/** `current` and `autocontinue`: "false:0, true:1"; the codec keeps any uint8 (#148). */
private val FLAG = 0..1

/**
 * The items of one upload, checked against the protocol's rules outside the codec (ADR 0010): built only by [of],
 * so an upload never sends an item that breaks them.
 */
internal class MissionUploadPlan private constructor(val items: List<MissionItemInt>) {

    val count: Int get() = items.size

    /** "0 on upload to the vehicle from GCS" (`opaque_id`, `common.xml`). */
    fun countMessage(): MissionCount = MissionCount(
        targetSystem = items.first().targetSystem,
        targetComponent = items.first().targetComponent,
        count = count,
        missionType = MAV_MISSION_TYPE_MISSION,
        opaqueId = 0,
    )

    companion object {

        /**
         * `null` unless every item has `seq` equal to its index, the first item's target, the mission type and
         * `current` and `autocontinue` 0 or 1, and item 0 is home's placeholder: ArduPilot never stores it ("it must
         * be home", `AP_Mission.cpp`), and it goes as QGroundControl sends it (NAV_WAYPOINT, `MAV_FRAME_GLOBAL`,
         * `current` 1).
         */
        fun of(items: List<MissionItemInt>): MissionUploadPlan? {
            val home = items.firstOrNull() ?: return null
            val valid = items.size <= MAX_COUNT &&
                home.isHomePlaceholder() &&
                items.withIndex().all { (index, item) ->
                    item.seq == index &&
                        item.targetSystem == home.targetSystem &&
                        item.targetComponent == home.targetComponent &&
                        item.missionType == MAV_MISSION_TYPE_MISSION &&
                        item.current in FLAG &&
                        item.autocontinue in FLAG
                }
            return if (valid) MissionUploadPlan(items) else null
        }

        private fun MissionItemInt.isHomePlaceholder(): Boolean =
            command == MAV_CMD_NAV_WAYPOINT && frame == MAV_FRAME_GLOBAL && current == 1
    }
}
