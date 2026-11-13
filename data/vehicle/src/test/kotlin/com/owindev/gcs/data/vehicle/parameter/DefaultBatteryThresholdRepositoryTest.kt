package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.domain.battery.BatteryFailsafeLevel
import com.owindev.gcs.core.domain.battery.BatteryMonitor
import com.owindev.gcs.core.domain.battery.BatteryThreshold
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.battery.FailsafeAction
import com.owindev.gcs.core.domain.battery.LowVoltageTimer
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_1
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_2
import com.owindev.gcs.data.vehicle.message.received
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The vehicles are a hand-fed contract; the store is the real one. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-028")
class DefaultBatteryThresholdRepositoryTest {

    private val testScope = TestScope()
    private val vehicleRepository: VehicleRepository = mockk()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val activeVehicle = ActiveVehicle()
    private val store = ParameterStore(activeVehicle)
    private val session = MutableStateFlow(FIRST_SESSION)

    private lateinit var repository: DefaultBatteryThresholdRepository

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns vehicles
        every { vehicleRepository.activeSystemId } returns activeSystemId
        repository =
            DefaultBatteryThresholdRepository(store, vehicleRepository, sessionOn(session), testScope.backgroundScope)
    }

    @Test
    fun `GIVEN the active vehicle's nine parameters WHEN observing THEN they are its battery settings`() =
        testScope.runTest {
            activate(MESSAGE_SYSTEM_1)
            SITL_BATTERY_PARAMETERS.forEach(::record)
            repository.activeThresholds.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeThresholds.value

            result shouldBeEqualTo SITL_THRESHOLDS
        }

    @Test
    fun `GIVEN only some parameters read WHEN observing THEN the others are unknown`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        record(paramValue("BATT_LOW_VOLT", LOW_VOLTS, MAV_PARAM_TYPE_REAL32))
        repository.activeThresholds.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeThresholds.value

        result shouldBeEqualTo BatteryThresholds.UNKNOWN.copy(
            low = BatteryFailsafeLevel.UNKNOWN.copy(voltage = BatteryThreshold.At(LOW_VOLTS)),
        )
    }

    @Test
    fun `GIVEN the settings shown WHEN the vehicle announces a change THEN the change is shown`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        SITL_BATTERY_PARAMETERS.forEach(::record)
        repository.activeThresholds.launchIn(backgroundScope)
        runCurrent()

        record(paramValue("BATT_FS_LOW_ACT", ACTION_RTL.toFloat(), MAV_PARAM_TYPE_INT8))
        runCurrent()
        val result = repository.activeThresholds.value.low.action

        result shouldBeEqualTo FailsafeAction.Acts(ACTION_RTL)
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN parameters but no active vehicle WHEN observing THEN nothing is known`() = testScope.runTest {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        SITL_BATTERY_PARAMETERS.forEach(::record)
        repository.activeThresholds.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeThresholds.value

        result shouldBeEqualTo BatteryThresholds.UNKNOWN
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN one vehicle's parameters WHEN another is the active one THEN nothing is known`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        SITL_BATTERY_PARAMETERS.forEach(::record)
        repository.activeThresholds.launchIn(backgroundScope)
        runCurrent()

        activeSystemId.value = SystemId(MESSAGE_SYSTEM_2)
        runCurrent()
        val result = repository.activeThresholds.value

        result shouldBeEqualTo BatteryThresholds.UNKNOWN
    }

    @Test
    fun `GIVEN the settings shown WHEN the vehicle reboots THEN nothing is known`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        SITL_BATTERY_PARAMETERS.forEach(::record)
        repository.activeThresholds.launchIn(backgroundScope)
        runCurrent()

        store.rebooted(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        runCurrent()
        val result = repository.activeThresholds.value

        result shouldBeEqualTo BatteryThresholds.UNKNOWN
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN live settings WHEN a replay starts before the store moves on THEN nothing is known`() =
        testScope.runTest {
            activate(MESSAGE_SYSTEM_1)
            SITL_BATTERY_PARAMETERS.forEach(::record)
            repository.activeThresholds.launchIn(backgroundScope)
            runCurrent()

            session.value = REPLAY_SESSION
            runCurrent()
            val result = repository.activeThresholds.value

            result shouldBeEqualTo BatteryThresholds.UNKNOWN
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay WHEN its vehicle's parameters were recorded THEN they are shown`() = testScope.runTest {
        store.startSession(REPLAY_SESSION)
        session.value = REPLAY_SESSION
        activate(MESSAGE_SYSTEM_1, REPLAY_SESSION)
        SITL_BATTERY_PARAMETERS.forEach { record(it, REPLAY_SESSION) }
        repository.activeThresholds.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeThresholds.value

        result shouldBeEqualTo SITL_THRESHOLDS
    }

    private fun activate(systemId: Int, session: SessionId = FIRST_SESSION) {
        if (session != FIRST_SESSION) activeVehicle.startReplay(session)
        activeVehicle.pinFirst(SystemId(systemId), session)
        activeSystemId.value = SystemId(systemId)
    }

    private fun record(message: MavlinkMessage, session: SessionId = FIRST_SESSION) =
        store.record(received(message, session))

    private companion object {
        const val LOW_VOLTS = 10.5f
        const val ACTION_RTL = 2
        const val MONITOR_VOLTAGE_AND_CURRENT = 4
        const val CAPACITY_MAH = 3300
        const val LOW_TIMER_SECONDS = 10
        val SITL_THRESHOLDS = BatteryThresholds(
            monitor = BatteryMonitor.Present(MONITOR_VOLTAGE_AND_CURRENT),
            low = BatteryFailsafeLevel(
                BatteryThreshold.At(LOW_VOLTS),
                BatteryThreshold.Disabled,
                FailsafeAction.WarnOnly,
            ),
            critical = BatteryFailsafeLevel(
                BatteryThreshold.Disabled,
                BatteryThreshold.Disabled,
                FailsafeAction.WarnOnly,
            ),
            lowVoltageTimer = LowVoltageTimer.Seconds(LOW_TIMER_SECONDS),
            capacityMah = CAPACITY_MAH,
        )
    }
}
