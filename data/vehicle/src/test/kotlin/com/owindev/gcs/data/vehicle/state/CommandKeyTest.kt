package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The key [DefaultVehicleRepository] gives a command confirmed now, from real SITL HEARTBEATs through the pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-050")
class CommandKeyTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val telemetryStore = TelemetryStore(TrailRule(StalenessRule()), ActiveVehicle())

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), any()) } returns SendResult.Sent
        coEvery { telemetryRequester.run(any(), any()) } coAnswers { awaitCancellation() }
        repository = testScope.repositoryOn(transport, telemetryRequester, telemetryStore)
    }

    @Test
    fun `GIVEN the active vehicle is heard WHEN reading the key THEN it names the vehicle and its connection`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)

            val result = repository.commandKey.value

            result shouldBeEqualTo CommandKey(SYSTEM_1, repository.activeConnection)
        }

    @Test
    fun `GIVEN the vehicle is lost and heard again WHEN reading the key THEN it moved on each change`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    delay(WELL_PAST_TIMEOUT)
                    emit(datagramOf(DISARMED_STABILIZE))
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)
            val heard = repository.commandKey.value
            delay(WELL_PAST_TIMEOUT - AFTER_TWO_GAPS)
            val lost = repository.commandKey.value
            delay(AFTER_TWO_GAPS)

            val result = repository.commandKey.value

            listOf(heard, lost, result).map { it?.connection }.distinct().size shouldBeEqualTo 3
            result?.systemId shouldBeEqualTo SYSTEM_1
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay plays its vehicle WHEN reading the key THEN there is none`() = testScope.runTest {
        val activeVehicle = ActiveVehicle()
        activeVehicle.startReplay(REPLAY_SESSION)
        activeVehicle.pinFirst(SYSTEM_1, REPLAY_SESSION)
        val replaying = repositoryOn(
            transport,
            telemetryRequester,
            telemetryStore,
            activeVehicle = activeVehicle,
            session = sessionOn(MutableStateFlow(REPLAY_SESSION)),
        )
        runCurrent()

        val result = listOf(replaying.commandKey.value, replaying.currentKey)

        result shouldBeEqualTo listOf(null, null)
    }
}
