package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import com.owindev.gcs.data.vehicle.parameter.BatteryParameter
import com.owindev.gcs.data.vehicle.parameter.BatteryParameterRequester
import com.owindev.gcs.data.vehicle.parameter.FollowedParameters
import com.owindev.gcs.data.vehicle.parameter.ParameterStore
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The listener and the watchdog feeding the parameter store, with pymavlink's PARAM_VALUE through the pipeline, and
 * telling the parameter requester of the active vehicle's link changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-028")
class ParametersFromLinkTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val activeVehicle = ActiveVehicle()
    private val session = testScope.sessionOn(transport, activeVehicle)
    private val parameterStore = ParameterStore(activeVehicle)

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), any()) } returns SendResult.Sent
        coEvery { telemetryRequester.run(any(), any()) } coAnswers { awaitCancellation() }
        repository = testScope.repositoryOn(
            transport = transport,
            telemetryRequester = telemetryRequester,
            parameterRequester = idleParameterRequester(),
            telemetryStore = TelemetryStore(TrailRule(StalenessRule()), activeVehicle),
            activeVehicle = activeVehicle,
            session = session,
            parameterStore = parameterStore,
        )
    }

    @Test
    fun `GIVEN the active vehicle WHEN its battery parameter comes on the link THEN the store keeps it`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE, GAP to BATT_LOW_VOLT)
            repository.vehicles.launchIn(backgroundScope)

            delay(HEARD)
            val result = parameterStore.parameters.value.values

            result shouldBeEqualTo mapOf(BatteryParameter.LOW_VOLT to LOW_VOLTS)
        }

    @Test
    fun `GIVEN the active vehicle WHEN ArduCopter answers a whole round THEN the store keeps all nine`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                *SITL_ROUND.keys.map { fixture -> GAP to fixture }.toTypedArray(),
            )
            repository.vehicles.launchIn(backgroundScope)

            delay(ROUND_HEARD)
            val result = parameterStore.parameters.value.values

            result shouldBeEqualTo SITL_ROUND.values.toMap()
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the active vehicle LOST WHEN it is heard again THEN its parameters are requested again`() =
        testScope.runTest {
            val parameterRequester: BatteryParameterRequester = mockk()
            val parameterTargets: CapturingSlot<Flow<VehicleTarget?>> = slot()
            coEvery { parameterRequester.run(capture(parameterTargets), any()) } coAnswers { awaitCancellation() }
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                WELL_PAST_TIMEOUT to DISARMED_LOITER,
            )
            repositoryOn(
                transport = transport,
                telemetryRequester = telemetryRequester,
                telemetryStore = TelemetryStore(TrailRule(StalenessRule()), activeVehicle),
                activeVehicle = activeVehicle,
                session = session,
                parameterRequester = parameterRequester,
            ).vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = parameterTargets.captured.take(3).toList()

            result shouldBeEqualTo listOf(
                VehicleTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
                null,
                VehicleTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
            )
        }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a battery parameter kept WHEN the vehicle reboots THEN it is forgotten`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(
            GAP to DISARMED_STABILIZE,
            GAP to BATT_LOW_VOLT,
            GAP to ATTITUDE_LONG_UP,
            GAP to ATTITUDE_AFTER_REBOOT,
        )
        repository.vehicles.launchIn(backgroundScope)

        delay(HEARD)
        val result = parameterStore.parameters.value.values

        result shouldBeEqualTo emptyMap()
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a battery parameter kept WHEN a replay starts THEN it is forgotten`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE, GAP to BATT_LOW_VOLT)
        repository.vehicles.launchIn(backgroundScope)
        delay(HEARD)

        session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
        runCurrent()
        val result = parameterStore.parameters.value

        result shouldBeEqualTo FollowedParameters.empty(session.session.value)
    }
}

private fun emptySource(): DatagramSource = object : DatagramSource {
    override fun datagrams() = emptyFlow<Datagram>()
}

/** `BATT_LOW_VOLT` 10.5, REAL32, from system 1 component 1 (pymavlink, resources `README.md`). */
private const val BATT_LOW_VOLT = "derived/param-value-batt-low-volt.bin"
private const val LOW_VOLTS = 10.5f

/** ArduCopter 4.7.3-beta's real answers, with the values pymavlink decodes (resources `README.md`). */
private val SITL_ROUND = mapOf(
    "param/batt-monitor.bin" to (BatteryParameter.MONITOR to 4f),
    "param/batt-low-volt.bin" to (BatteryParameter.LOW_VOLT to 10.8f),
    "param/batt-crt-volt.bin" to (BatteryParameter.CRT_VOLT to 0f),
    "param/batt-low-mah.bin" to (BatteryParameter.LOW_MAH to 0f),
    "param/batt-crt-mah.bin" to (BatteryParameter.CRT_MAH to 0f),
    "param/batt-low-timer.bin" to (BatteryParameter.LOW_TIMER to 10f),
    "param/batt-capacity.bin" to (BatteryParameter.CAPACITY to 3000f),
    "param/batt-fs-low-act.bin" to (BatteryParameter.FS_LOW_ACT to 2f),
    "param/batt-fs-crt-act.bin" to (BatteryParameter.FS_CRT_ACT to 0f),
)

/** Every datagram delivered, well within the 3 s link-loss timeout (REQ-017). */
private const val HEARD = 500L

/** A HEARTBEAT and the nine answers, [GAP] apart, all delivered and still within the link-loss timeout. */
private const val ROUND_HEARD = 1_500L

/** `time_boot_ms` 4000000000 and then 397119 (pymavlink): back by far more than the reboot backstep. */
private const val ATTITUDE_LONG_UP = "derived/attitude-edges.bin"
private const val ATTITUDE_AFTER_REBOOT = "telemetry/attitude.bin"
