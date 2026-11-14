package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.message.MAV_COMP_ID_GIMBAL
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_1
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_2
import com.owindev.gcs.data.vehicle.message.heartbeat
import com.owindev.gcs.data.vehicle.message.received
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class ParameterStoreTest {

    private val activeVehicle = ActiveVehicle()

    private lateinit var store: ParameterStore

    @BeforeEach
    fun setUp() {
        store = ParameterStore(activeVehicle)
    }

    @Test
    fun `GIVEN the active vehicle WHEN it sends its nine battery parameters THEN each is kept with its value`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters(FIRST_SESSION, SystemId(MESSAGE_SYSTEM_1), SITL_VALUES)
    }

    @Test
    fun `GIVEN a value kept WHEN the vehicle announces a change THEN the new value replaces it`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))

        store.record(received(paramValue(LOW_VOLT, CHANGED_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value.values

        result shouldBeEqualTo mapOf(BatteryParameter.LOW_VOLT to CHANGED_VOLTS)
    }

    @Test
    fun `GIVEN a value kept WHEN the same value comes again THEN nothing is published`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val before = store.parameters.value

        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value

        result shouldBe before
    }

    @Test
    fun `GIVEN a value kept WHEN another message arrives THEN nothing is published`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val before = store.parameters.value

        store.record(received(heartbeat(armed = false), FIRST_SESSION))
        val result = store.parameters.value

        result shouldBe before
    }

    @Tag("REQ-016")
    @Tag("REQ-020")
    @Test
    fun `GIVEN another system ID WHEN it sends a battery parameter THEN it is not kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val value = paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32)

        store.record(received(value, FIRST_SESSION, systemId = MESSAGE_SYSTEM_2))
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(FIRST_SESSION)
    }

    @Test
    fun `GIVEN another component of the vehicle WHEN it sends a battery parameter THEN it is not kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val value = paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32)

        store.record(received(value, FIRST_SESSION, componentId = MAV_COMP_ID_GIMBAL))
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(FIRST_SESSION)
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN no active vehicle yet WHEN a battery parameter arrives THEN it is not kept`() {
        val value = paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32)

        store.record(received(value, FIRST_SESSION))
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(FIRST_SESSION)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay WHEN a live battery parameter arrives late THEN it is not kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.startSession(REPLAY_SESSION)

        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(REPLAY_SESSION)
    }

    @Test
    fun `GIVEN a parameter this GCS does not read WHEN it arrives THEN it is neither kept nor counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue("MOT_THST_HOVER", HOVER_THRUST, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 0)
    }

    @Test
    fun `GIVEN a name that is not ASCII WHEN it arrives THEN it is ignored and counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue("BATT_LOW_VOLT�", LOW_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 1)
    }

    @Test
    fun `GIVEN a non-ASCII name of a parameter this GCS does not read WHEN it arrives THEN it is counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue("MOT_É", HOVER_THRUST, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.malformed

        result shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a battery parameter of another type WHEN it arrives THEN it is ignored and counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(LOW_VOLT, LOW_VOLTS, MAV_PARAM_TYPE_INT32), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 1)
    }

    @Test
    fun `GIVEN a threshold that is not finite WHEN it arrives THEN it is ignored and counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(LOW_VOLT, Float.NaN, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 1)
    }

    @ParameterizedTest
    @ValueSource(floats = [1.5f, 128f, -129f])
    fun `GIVEN an action an int8 cannot hold WHEN it arrives THEN it is ignored and counted`(action: Float) {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(FS_LOW_ACT, action, MAV_PARAM_TYPE_INT8), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 1)
    }

    @ParameterizedTest
    @ValueSource(floats = [127f, -128f])
    fun `GIVEN an action at the int8 limits WHEN it arrives THEN it is kept`(action: Float) {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(FS_LOW_ACT, action, MAV_PARAM_TYPE_INT8), FIRST_SESSION))
        val result = store.parameters.value.values

        result shouldBeEqualTo mapOf(BatteryParameter.FS_LOW_ACT to action)
    }

    @Test
    fun `GIVEN a capacity beyond the exact float range WHEN it arrives THEN it is ignored and counted`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(CAPACITY, ABOVE_EXACT_INTEGERS, MAV_PARAM_TYPE_INT32), FIRST_SESSION))
        val result = store.parameters.value to store.malformed

        result shouldBeEqualTo (FollowedParameters.empty(FIRST_SESSION) to 1)
    }

    @Test
    fun `GIVEN a capacity at the exact float limit WHEN it arrives THEN it is kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)

        store.record(received(paramValue(CAPACITY, EXACT_INTEGER_LIMIT, MAV_PARAM_TYPE_INT32), FIRST_SESSION))
        val result = store.parameters.value.values

        result shouldBeEqualTo mapOf(BatteryParameter.CAPACITY to EXACT_INTEGER_LIMIT)
    }

    @Test
    fun `GIVEN values kept WHEN the vehicle reboots THEN they are all forgotten`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

        store.rebooted(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters(FIRST_SESSION, SystemId(MESSAGE_SYSTEM_1), emptyMap())
    }

    @Test
    fun `GIVEN values kept WHEN another system ID reboots THEN they are kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

        store.rebooted(SystemId(MESSAGE_SYSTEM_2), FIRST_SESSION)
        val result = store.parameters.value.values

        result shouldBeEqualTo SITL_VALUES
    }

    @Test
    fun `GIVEN values kept WHEN they are forgotten THEN the vehicle reads as nothing read`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

        store.forget(SystemId(MESSAGE_SYSTEM_1))
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters(FIRST_SESSION, SystemId(MESSAGE_SYSTEM_1), emptyMap())
    }

    @Test
    fun `GIVEN values kept WHEN another system ID's are forgotten THEN they are kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

        store.forget(SystemId(MESSAGE_SYSTEM_2))
        val result = store.parameters.value.values

        result shouldBeEqualTo SITL_VALUES
    }

    @Test
    fun `GIVEN values kept WHEN a reboot of the session before arrives THEN they are kept`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        store.startSession(REPLAY_SESSION)
        activeVehicle.startReplay(REPLAY_SESSION)
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), REPLAY_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, REPLAY_SESSION)) }

        store.rebooted(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        val result = store.parameters.value.values

        result shouldBeEqualTo SITL_VALUES
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN values kept WHEN a replay starts THEN they are all forgotten`() {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

        store.startSession(REPLAY_SESSION)
        val result = store.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(REPLAY_SESSION)
    }

    private companion object {
        const val LOW_VOLT = "BATT_LOW_VOLT"
        const val FS_LOW_ACT = "BATT_FS_LOW_ACT"
        const val CAPACITY = "BATT_CAPACITY"
        const val LOW_VOLTS = 10.5f
        const val CHANGED_VOLTS = 10.8f
        const val HOVER_THRUST = 0.39f
        const val EXACT_INTEGER_LIMIT = 16_777_216f
        const val ABOVE_EXACT_INTEGERS = 16_777_218f
        val SITL_VALUES = mapOf(
            BatteryParameter.MONITOR to 4f,
            BatteryParameter.LOW_VOLT to 10.5f,
            BatteryParameter.CRT_VOLT to 0f,
            BatteryParameter.LOW_MAH to 0f,
            BatteryParameter.CRT_MAH to 0f,
            BatteryParameter.LOW_TIMER to 10f,
            BatteryParameter.CAPACITY to 3300f,
            BatteryParameter.FS_LOW_ACT to 0f,
            BatteryParameter.FS_CRT_ACT to 0f,
        )
    }
}
