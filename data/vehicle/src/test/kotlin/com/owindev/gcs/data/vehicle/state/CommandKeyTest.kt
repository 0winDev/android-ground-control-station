package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @Test
    fun `GIVEN the watchdog's timer runs late WHEN a HEARTBEAT ends a gap past the timeout THEN the key moved`() =
        testScope.runTest {
            val clock = TestTimeSource()
            val lateWatchdog = repositoryOn(transport, telemetryRequester, telemetryStore, timeSource = clock)
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE, GAP to DISARMED_STABILIZE)
            lateWatchdog.vehicles.launchIn(backgroundScope)
            delay(GAP + HALF_GAP)
            val before = lateWatchdog.currentKey
            clock += LinkLossRule().timeout + ONE_SECOND

            delay(GAP)
            val result = lateWatchdog.currentKey

            (result?.connection == before?.connection) shouldBeEqualTo false
        }

    @Tag("REQ-033")
    @Test
    fun `GIVEN the active vehicle CONNECTED WHEN it reboots without being LOST THEN the key moved`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                GAP to ATTITUDE_LONG_UP,
                GAP to ATTITUDE_AFTER_REBOOT,
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(GAP + GAP + HALF_GAP)
            val before = repository.currentKey

            delay(GAP)
            val result = repository.currentKey

            (result?.connection == before?.connection) shouldBeEqualTo false
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay played and stopped WHEN reading the live key THEN it is not the key from before`() =
        testScope.runTest {
            val activeVehicle = ActiveVehicle()
            val session = sessionOn(transport, activeVehicle)
            val switching = repositoryOn(transport, telemetryRequester, telemetryStore, activeVehicle, session)
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE),
                datagrams(GAP to DISARMED_STABILIZE),
            )
            switching.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)
            val before = switching.currentKey
            session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
            runCurrent()
            session.live()

            delay(AFTER_TWO_GAPS)
            val result = switching.currentKey

            (result?.connection == before?.connection) shouldBeEqualTo false
            result?.systemId shouldBeEqualTo SYSTEM_1
        }

    @Tag("REQ-048")
    @Test
    fun `GIVEN the vehicle is lost and heard again WHEN each change shows THEN the key moved before it`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    delay(WELL_PAST_TIMEOUT)
                    emit(datagramOf(DISARMED_STABILIZE))
                    awaitCancellation()
                },
            )
            val shown = mutableListOf<CommandKey?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.vehicles.first { it[SYSTEM_1]?.link == VehicleLink.LOST }
                shown += repository.currentKey
                repository.vehicles.first { it[SYSTEM_1]?.link == VehicleLink.CONNECTED }
                shown += repository.currentKey
            }
            delay(AFTER_TWO_GAPS)
            val whileConnected = repository.currentKey
            delay(WELL_PAST_TIMEOUT - AFTER_TWO_GAPS)
            val whileLost = repository.currentKey

            delay(AFTER_TWO_GAPS)
            val result = shown.toList()

            result.zip(listOf(whileConnected, whileLost)).map { (shownKey, before) ->
                shownKey != before
            } shouldBeEqualTo
                listOf(true, true)
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

private const val HALF_GAP = 50L
private val ONE_SECOND = 1.seconds

/** `time_boot_ms` 4000000000 and then 397119 (pymavlink): back by far more than the reboot backstep. */
private const val ATTITUDE_LONG_UP = "derived/attitude-edges.bin"
private const val ATTITUDE_AFTER_REBOOT = "telemetry/attitude.bin"
