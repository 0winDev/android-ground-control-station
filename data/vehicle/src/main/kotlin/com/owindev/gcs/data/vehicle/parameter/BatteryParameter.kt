package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.domain.battery.BatteryFailsafeLevel
import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeAction
import com.owindev.gcs.core.domain.battery.LowVoltageTimer

/**
 * A parameter of the first battery that this GCS reads (REQ-028), with the `MAV_PARAM_TYPE` ArduPilot sends it with:
 * the type of its declaration in `AP_BattMonitor_Params.h` (`AP_Float`, `AP_Int32`, `AP_Int8`, `AP_Enum` stored as
 * int8), as ArduCopter 4.7.2 sent them in `tlog/mission-planner.tlog`. A second battery's `BATT2_*` are not read.
 */
internal enum class BatteryParameter(val id: String, val type: Int) {
    MONITOR("BATT_MONITOR", MAV_PARAM_TYPE_INT8),
    LOW_VOLT("BATT_LOW_VOLT", MAV_PARAM_TYPE_REAL32),
    CRT_VOLT("BATT_CRT_VOLT", MAV_PARAM_TYPE_REAL32),
    LOW_MAH("BATT_LOW_MAH", MAV_PARAM_TYPE_REAL32),
    CRT_MAH("BATT_CRT_MAH", MAV_PARAM_TYPE_REAL32),
    LOW_TIMER("BATT_LOW_TIMER", MAV_PARAM_TYPE_INT8),
    CAPACITY("BATT_CAPACITY", MAV_PARAM_TYPE_INT32),
    FS_LOW_ACT("BATT_FS_LOW_ACT", MAV_PARAM_TYPE_INT8),
    FS_CRT_ACT("BATT_FS_CRT_ACT", MAV_PARAM_TYPE_INT8),
    ;

    /**
     * The value [wire] stands for, or `null` if it cannot be one of this parameter's. ArduPilot casts every value to
     * a float (`AP_Param::cast_to_float`, sent by `GCS_Param.cpp`): an integer is taken only within 2²⁴, where that
     * cast is exact, and none of these needs more.
     */
    fun valueOf(wire: Float): Float? = when {
        !wire.isFinite() -> null
        type == MAV_PARAM_TYPE_REAL32 -> wire
        wire != wire.toInt().toFloat() || wire !in rangeOf(type) -> null
        else -> wire
    }

    companion object {
        private val byId: Map<String, BatteryParameter> = entries.associateBy(BatteryParameter::id)

        fun withId(id: String): BatteryParameter? = byId[id]

        private fun rangeOf(type: Int): ClosedFloatingPointRange<Float> = when (type) {
            MAV_PARAM_TYPE_INT8 -> Byte.MIN_VALUE.toFloat()..Byte.MAX_VALUE.toFloat()
            else -> -EXACT_INTEGER_LIMIT..EXACT_INTEGER_LIMIT
        }

        /** 2²⁴: every integer up to it is a float exactly. */
        private const val EXACT_INTEGER_LIMIT = 16_777_216f
    }
}

/** The settings these [values] give, each unknown until its parameter has been read. */
internal fun batteryThresholdsOf(values: Map<BatteryParameter, Float>) = BatteryThresholds(
    monitor = BatteryMonitor.of(values[BatteryParameter.MONITOR]?.toInt()),
    low = BatteryFailsafeLevel(
        voltage = BatteryThreshold.of(values[BatteryParameter.LOW_VOLT]),
        remainingMah = BatteryThreshold.of(values[BatteryParameter.LOW_MAH]),
        action = FailsafeAction.of(values[BatteryParameter.FS_LOW_ACT]?.toInt()),
    ),
    critical = BatteryFailsafeLevel(
        voltage = BatteryThreshold.of(values[BatteryParameter.CRT_VOLT]),
        remainingMah = BatteryThreshold.of(values[BatteryParameter.CRT_MAH]),
        action = FailsafeAction.of(values[BatteryParameter.FS_CRT_ACT]?.toInt()),
    ),
    lowVoltageTimer = LowVoltageTimer.of(values[BatteryParameter.LOW_TIMER]?.toInt()),
    capacityMah = values[BatteryParameter.CAPACITY]?.toInt(),
)

// MAV_PARAM_TYPE (common.xml).
private const val MAV_PARAM_TYPE_INT8 = 2
private const val MAV_PARAM_TYPE_INT32 = 6
private const val MAV_PARAM_TYPE_REAL32 = 9
