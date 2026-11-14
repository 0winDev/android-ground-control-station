package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue

// MAV_PARAM_TYPE (common.xml).
internal const val MAV_PARAM_TYPE_INT8 = 2
internal const val MAV_PARAM_TYPE_INT32 = 6
internal const val MAV_PARAM_TYPE_REAL32 = 9

/** ArduCopter 4.7.2's battery parameters in `tlog/mission-planner.tlog` (`:core:mavlink`), decoded by pymavlink. */
internal const val SITL_PARAM_COUNT = 1370
internal val SITL_BATTERY_PARAMETERS = listOf(
    paramValue("BATT_MONITOR", 4f, MAV_PARAM_TYPE_INT8),
    paramValue("BATT_LOW_VOLT", 10.5f, MAV_PARAM_TYPE_REAL32),
    paramValue("BATT_CRT_VOLT", 0f, MAV_PARAM_TYPE_REAL32),
    paramValue("BATT_LOW_MAH", 0f, MAV_PARAM_TYPE_REAL32),
    paramValue("BATT_CRT_MAH", 0f, MAV_PARAM_TYPE_REAL32),
    paramValue("BATT_LOW_TIMER", 10f, MAV_PARAM_TYPE_INT8),
    paramValue("BATT_CAPACITY", 3300f, MAV_PARAM_TYPE_INT32),
    paramValue("BATT_FS_LOW_ACT", 0f, MAV_PARAM_TYPE_INT8),
    paramValue("BATT_FS_CRT_ACT", 0f, MAV_PARAM_TYPE_INT8),
)

/** An answer to a request by name: ArduPilot echoes `param_index` -1 (`GCS_Param.cpp`), 65535 on the wire. */
internal fun paramValue(id: String, value: Float, type: Int) =
    ParamValue(paramId = id, value = value, type = type, count = SITL_PARAM_COUNT, index = UNKNOWN_INDEX)

private const val UNKNOWN_INDEX = 65_535
