package com.owindev.gcs.data.vehicle.state

import android.util.Log
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import com.owindev.gcs.data.vehicle.telemetry.TelemetryTarget
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real ArduCopter SITL HEARTBEATs through the whole pipeline (transport → parser → decoder → mapper →
 * watchdog), in virtual time: the repository's clock is the test scheduler's, so no test sleeps for real.
 * Expected values come from pymavlink and the ArduCopter docs (`src/test/resources/README.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVehicleRepositoryTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val telemetryTargets: CapturingSlot<Flow<TelemetryTarget?>> = slot()
    private val telemetryStore = TelemetryStore(TrailRule(StalenessRule()))

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), any()) } returns SendResult.Sent
        coEvery { telemetryRequester.run(capture(telemetryTargets), any()) } coAnswers { awaitCancellation() }
        repository = testScope.repositoryOn(transport, telemetryRequester, telemetryStore)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN real SITL HEARTBEATs WHEN observing the vehicles THEN follows the mode and armed state`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                GAP to DISARMED_GUIDED,
                GAP to DISARMED_LOITER,
                GAP to ARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(5).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
                seen(SYSTEM_1 to connected(LOITER_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_ARMED)),
            )
        }

    @Tag("REQ-016")
    @Test
    fun `GIVEN HEARTBEATs from two systems WHEN observing the vehicles THEN keeps each one apart`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_LOITER,
                GAP to SYSTEM_2_LOITER,
                GAP to ARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(LOITER_DISARMED)),
                seen(SYSTEM_1 to connected(LOITER_DISARMED), SYSTEM_2 to connected(LOITER_DISARMED_2)),
                seen(SYSTEM_1 to connected(GUIDED_ARMED), SYSTEM_2 to connected(LOITER_DISARMED_2)),
            )
        }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a ground station HEARTBEAT between the vehicle ones WHEN observing the vehicles THEN is ignored`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                GAP to GCS,
                GAP to DISARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a vehicle that goes silent WHEN the timeout passes THEN is LOST exactly then with its last state`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)

            val result = repository.vehicles.first { it.anyLost() }

            result.seen() shouldBeEqualTo seen(SYSTEM_1 to lost(STABILIZE_DISARMED))
            result.getValue(SYSTEM_1).lastHeartbeat.elapsedNow() shouldBeEqualTo TIMEOUT
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a HEARTBEAT late but within the timeout WHEN observing the vehicles THEN never flaps to LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                JUST_UNDER_TIMEOUT to DISARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
                seen(SYSTEM_1 to lost(GUIDED_DISARMED)),
            )
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a lost vehicle WHEN its HEARTBEATs resume THEN recovers with the new state`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(
            GAP to DISARMED_STABILIZE,
            WELL_PAST_TIMEOUT to ARMED_GUIDED,
        )

        val result = repository.vehicles.map { it.seen() }.take(4).toList()

        result shouldBeEqualTo listOf(
            emptyMap(),
            seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
            seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            seen(SYSTEM_1 to connected(GUIDED_ARMED)),
        )
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN two systems WHEN one goes silent THEN only that one is LOST`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(
            GAP to DISARMED_LOITER,
            GAP to SYSTEM_2_LOITER,
            HALF_TIMEOUT to ARMED_GUIDED,
        )

        val result = repository.vehicles.map { it.seen() }.take(6).toList()

        result shouldBeEqualTo listOf(
            emptyMap(),
            seen(SYSTEM_1 to connected(LOITER_DISARMED)),
            seen(SYSTEM_1 to connected(LOITER_DISARMED), SYSTEM_2 to connected(LOITER_DISARMED_2)),
            seen(SYSTEM_1 to connected(GUIDED_ARMED), SYSTEM_2 to connected(LOITER_DISARMED_2)),
            seen(SYSTEM_1 to connected(GUIDED_ARMED), SYSTEM_2 to lost(LOITER_DISARMED_2)),
            seen(SYSTEM_1 to lost(GUIDED_ARMED), SYSTEM_2 to lost(LOITER_DISARMED_2)),
        )
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN only ground station HEARTBEATs after the vehicle's WHEN the timeout passes THEN the vehicle is LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                HALF_TIMEOUT to GCS,
                HALF_TIMEOUT to GCS,
                HALF_TIMEOUT to GCS,
            )

            val result = repository.vehicles.map { it.seen() }.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the transport fails after a HEARTBEAT WHEN the timeout passes THEN the vehicle is LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE) { throw SocketException() },
                // Every reopening attempt fails too: the link stays down.
                datagrams { throw SocketException() },
            )

            val result = repository.vehicles.map { it.seen() }.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the datagrams end after a HEARTBEAT WHEN the timeout passes THEN the vehicle is LOST`() =
        testScope.runTest {
            // The session reopens a transport that ended (REQ-019); the new socket hears nothing.
            every { transport.datagrams() } returnsMany listOf(datagrams(GAP to DISARMED_STABILIZE) { }, datagrams())

            val result = repository.vehicles.map { it.seen() }.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Tag("REQ-019")
    @Test
    fun `GIVEN a stopped session WHEN a new one starts THEN the vehicle is LOST with its last state until heard`() =
        testScope.runTest {
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE),
                datagrams(HALF_TIMEOUT to ARMED_GUIDED),
            )
            val firstObserver = repository.vehicles.launchIn(backgroundScope)
            delay(GAP * 2)
            firstObserver.cancel()
            delay(SESSION_STOPPED)

            val result = repository.vehicles.map { it.seen() }.take(2).toList()

            result shouldBeEqualTo listOf(
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_ARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a vehicle still CONNECTED when the session stopped WHEN observed much later THEN is LOST at once`() =
        testScope.runTest {
            every { transport.datagrams() } returnsMany listOf(heartbeatEverySecond(DISARMED_STABILIZE), datagrams())
            val firstObserver = repository.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)
            firstObserver.cancel()
            delay(SESSION_STOPPED)

            val result = repository.vehicles.first()

            result.seen() shouldBeEqualTo seen(SYSTEM_1 to lost(STABILIZE_DISARMED))
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a session stopped just after a HEARTBEAT WHEN observed before the timeout THEN goes LOST on time`() =
        testScope.runTest {
            every { transport.datagrams() } returnsMany listOf(heartbeatEverySecond(DISARMED_STABILIZE), datagrams())
            val firstObserver = repository.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)
            firstObserver.cancel()
            delay(UNTIL_SESSION_STOPPED_BEFORE_TIMEOUT)

            val result = repository.vehicles.take(2).toList()

            result.map { it.seen() } shouldBeEqualTo listOf(
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            )
            result.last().getValue(SYSTEM_1).lastHeartbeat.elapsedNow() shouldBeEqualTo TIMEOUT
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a HEARTBEAT 1 ms before the timeout WHEN observing the vehicles THEN never flaps to LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                TIMEOUT_MILLIS - 1 to DISARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
                seen(SYSTEM_1 to lost(GUIDED_DISARMED)),
            )
        }

    /**
     * At exactly the timeout the HEARTBEAT and the timer are due at the same instant. Either order is safe
     * (LOST then CONNECTED, or CONNECTED throughout, since the vehicle was heard in time). Here the timer is
     * handled first, since the session hands each message over through its source switch (REQ-037): LOST at the
     * timeout, as the rule says, and CONNECTED again with the HEARTBEAT, never left LOST.
     */
    @Tag("REQ-017")
    @Test
    fun `GIVEN a HEARTBEAT exactly at the timeout WHEN observing the vehicles THEN is CONNECTED again right after`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                TIMEOUT_MILLIS to DISARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a custom timeout WHEN the vehicle goes silent THEN is LOST after that timeout`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
        val custom = repositoryOn(transport, telemetryRequester, telemetryStore, rule = LinkLossRule(CUSTOM_TIMEOUT))

        val result = custom.vehicles.first { it.anyLost() }

        result.getValue(SYSTEM_1).lastHeartbeat.elapsedNow() shouldBeEqualTo CUSTOM_TIMEOUT
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN only HEARTBEATs from another component of the vehicle WHEN the timeout passes THEN is LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                HALF_TIMEOUT to GIMBAL,
                HALF_TIMEOUT to GIMBAL,
                HALF_TIMEOUT to GIMBAL,
            )

            val result = repository.vehicles.map { it.seen() }.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
            )
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN only ground station HEARTBEATs WHEN observing the vehicles THEN there is no active vehicle`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to GCS, GAP to GCS)
            repository.vehicles.launchIn(backgroundScope)

            delay(AFTER_TWO_GAPS)

            repository.activeSystemId.value shouldBeEqualTo null
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a vehicle heard WHEN observing the vehicles THEN it is the active vehicle`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)

        repository.vehicles.first { it.isNotEmpty() }

        repository.activeSystemId.value shouldBeEqualTo SYSTEM_1
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a lower system ID heard after the first vehicle WHEN observing THEN the active one stays`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to SYSTEM_2_LOITER,
                GAP to DISARMED_LOITER,
            )

            repository.vehicles.first { it.size == 2 }

            repository.activeSystemId.value shouldBeEqualTo SYSTEM_2
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN the active vehicle is LOST and another is heard WHEN observing THEN the active one stays`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_LOITER,
                afterwards = { emitAll(heartbeatEverySecond(SYSTEM_2_LOITER)) },
            )

            repository.vehicles.first { it[SYSTEM_1]?.link == VehicleLink.LOST }

            repository.activeSystemId.value shouldBeEqualTo SYSTEM_1
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the transport fails while observed WHEN HEARTBEATs resume THEN the vehicle is LOST then CONNECTED`() =
        testScope.runTest {
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE) { throw SocketException() },
                datagrams(WELL_PAST_TIMEOUT to ARMED_GUIDED),
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to lost(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_ARMED)),
            )
        }

    @Tag("REQ-019")
    @Tag("REQ-021")
    @Test
    fun `GIVEN the transport fails while observed WHEN it recovers THEN this GCS announces itself only while it can`() =
        testScope.runTest {
            var socketOpen = true
            val sent = recordSends(isOpen = { socketOpen })
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE) { throw SocketException() }.onCompletion { socketOpen = false },
                datagrams(WELL_PAST_TIMEOUT to ARMED_GUIDED).onStart { socketOpen = true },
            )
            repository.vehicles.launchIn(backgroundScope)

            delay(SEVEN_AND_A_HALF_SECONDS)

            sent shouldBeEqualTo SENT_AROUND_THE_OUTAGE.map { it to SYSTEM_1_ADDRESS }
        }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the active vehicle is heard WHEN observing THEN this GCS announces itself to it once per second`() =
        testScope.runTest {
            val sent = recordSends()
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = { emitAll(heartbeatEverySecond(DISARMED_STABILIZE)) },
            )
            repository.vehicles.launchIn(backgroundScope)

            delay(FOUR_AND_A_HALF_SECONDS)

            sent shouldBeEqualTo listOf(1_000L, 2_000L, 3_000L, 4_000L).map { it to SYSTEM_1_ADDRESS }
        }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the active vehicle goes silent WHEN it is LOST THEN this GCS stops announcing itself`() =
        testScope.runTest {
            val sent = recordSends()
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            repository.vehicles.launchIn(backgroundScope)

            delay(SESSION_STOPPED)

            sent shouldBeEqualTo SENT_WHILE_CONNECTED.map { it to SYSTEM_1_ADDRESS }
        }

    @Tag("REQ-021")
    @Tag("REQ-020")
    @Test
    fun `GIVEN another vehicle still heard WHEN the active one is LOST THEN nothing is sent to the other one`() =
        testScope.runTest {
            val sent = recordSends()
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_LOITER,
                afterwards = { emitAll(heartbeatEverySecond(SYSTEM_2_LOITER)) },
            )
            repository.vehicles.launchIn(backgroundScope)

            delay(SESSION_STOPPED)

            sent shouldBeEqualTo SENT_WHILE_CONNECTED.map { it to SYSTEM_1_ADDRESS }
        }

    @Tag("REQ-021")
    @Test
    fun `GIVEN nobody observes the link any more WHEN the session closes THEN this GCS stops announcing itself`() =
        testScope.runTest {
            var socketOpen = true
            val sent = recordSends(isOpen = { socketOpen })
            every { transport.datagrams() } returnsMany listOf(
                heartbeatEverySecond(DISARMED_STABILIZE).onCompletion { socketOpen = false },
                datagrams(),
            )
            val observer = repository.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)
            observer.cancel()

            delay(SESSION_STOPPED * 2)

            sent.last() shouldBeEqualTo (LAST_TICK_BEFORE_SESSION_CLOSED to SYSTEM_1_ADDRESS)
        }

    @Tag("REQ-021")
    @Test
    fun `GIVEN the watchdog timer runs late after sleep WHEN the timeout has passed THEN nothing is sent`() =
        testScope.runTest {
            val sent = recordSends()
            val deviceClock = TestTimeSource()
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            val sleepy = repositoryOn(transport, telemetryRequester, telemetryStore, timeSource = deviceClock)
            sleepy.vehicles.launchIn(backgroundScope)
            delay(ONE_AND_A_HALF_SECONDS)
            // The device slept: its clock moved past the timeout while coroutine time (the watchdog's
            // timer) barely did, so the watchdog still says CONNECTED.
            deviceClock += WELL_PAST_TIMEOUT.milliseconds

            delay(ONE_SECOND)

            sent shouldBeEqualTo listOf(1_000L to SYSTEM_1_ADDRESS)
            sleepy.vehicles.value.getValue(SYSTEM_1).link shouldBeEqualTo VehicleLink.CONNECTED
        }

    @Tag("REQ-021")
    @Tag("REQ-017")
    @Test
    fun `GIVEN the watchdog fails WHEN HEARTBEATs keep arriving THEN this GCS stops announcing itself`() =
        testScope.runTest {
            val sent = recordSends()
            // Its jobs end with the test (child of backgroundScope); the expected crashes are swallowed.
            val appScope = CoroutineScope(
                backgroundScope.coroutineContext +
                    SupervisorJob(backgroundScope.coroutineContext.job) +
                    CoroutineExceptionHandler { _, _ -> },
            )
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = { emitAll(heartbeatEverySecond(DISARMED_STABILIZE)) },
            )
            val failing = repositoryOn(
                transport,
                telemetryRequester,
                telemetryStore,
                timeSource = FailsOnSecondMark(testScheduler.timeSource),
                scope = appScope,
            )
            failing.vehicles.launchIn(backgroundScope)

            delay(SESSION_STOPPED)

            sent shouldBeEqualTo listOf(1_000L to SYSTEM_1_ADDRESS)
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the watchdog fails WHEN HEARTBEATs keep arriving THEN every vehicle is LOST and the listener stops`() =
        testScope.runTest {
            val crashes = mutableListOf<Throwable>()
            val appScope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, crash ->
                    crashes += crash
                },
            )
            // The sender reads the same rule; idle here so that only the watchdog meets the failure.
            val idleSender: GcsHeartbeatSender = mockk()
            coEvery { idleSender.run(any()) } coAnswers { awaitCancellation() }
            val failingRule: LinkLossRule = mockk()
            every { failingRule.timeout } returns TIMEOUT
            every { failingRule.linkAfter(any()) } returns VehicleLink.CONNECTED andThenThrows IllegalStateException()
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                WELL_PAST_TIMEOUT to DISARMED_GUIDED,
            )
            val failing = repositoryOn(
                transport,
                telemetryRequester,
                telemetryStore,
                rule = failingRule,
                heartbeatSender = idleSender,
                scope = appScope,
            )
            failing.vehicles.launchIn(backgroundScope)

            delay(AFTER_WATCHDOG_FAILED)

            failing.vehicles.value.seen() shouldBeEqualTo seen(SYSTEM_1 to lost(STABILIZE_DISARMED))
            crashes.map { it::class } shouldBeEqualTo listOf(
                IllegalStateException::class,
                ClosedSendChannelException::class,
            )
        }

    @Tag("REQ-023")
    @Test
    fun `GIVEN the active vehicle is heard WHEN observing THEN its telemetry is requested at its address`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = { emitAll(heartbeatEverySecond(DISARMED_STABILIZE)) },
            )
            repository.vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = telemetryTargets.captured.take(1).toList()

            result shouldBeEqualTo listOf(TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS))
        }

    @Tag("REQ-023")
    @Tag("REQ-020")
    @Test
    fun `GIVEN another vehicle still heard WHEN the active one is LOST THEN no telemetry is requested from it`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_LOITER,
                afterwards = { emitAll(heartbeatEverySecond(SYSTEM_2_LOITER)) },
            )
            repository.vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = telemetryTargets.captured.take(2).toList()

            result shouldBeEqualTo listOf(TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS), null)
        }

    @Tag("REQ-023")
    @Tag("REQ-019")
    @Test
    fun `GIVEN the active vehicle is LOST WHEN it is heard again THEN its telemetry is requested again`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                WELL_PAST_TIMEOUT to DISARMED_GUIDED,
            )
            repository.vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = telemetryTargets.captured.take(3).toList()

            result shouldBeEqualTo listOf(
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
                null,
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
            )
        }

    @Tag("REQ-023")
    @Tag("REQ-033")
    @Test
    fun `GIVEN the active vehicle CONNECTED WHEN it reboots without being LOST THEN telemetry is requested again`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                GAP to ATTITUDE_LONG_UP,
                GAP to ATTITUDE_AFTER_REBOOT,
                afterwards = { emitAll(heartbeatEverySecond(DISARMED_STABILIZE)) },
            )
            repository.vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = telemetryTargets.captured.take(2).toList()

            result shouldBeEqualTo listOf(
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
            )
        }

    /**
     * The telemetry job busy while the vehicle is lost and heard again: both changes still reach it, so a
     * new round starts. A state that only kept the latest value would show CONNECTED twice and lose it.
     */
    @Tag("REQ-023")
    @Tag("REQ-019")
    @Test
    fun `GIVEN the telemetry job is busy WHEN the vehicle is lost and heard again THEN it still gets both changes`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                WELL_PAST_TIMEOUT to DISARMED_GUIDED,
            )
            repository.vehicles.launchIn(backgroundScope)
            runCurrent()

            val result = telemetryTargets.captured.onEach { delay(BUSY_PAST_RECOVERY) }.take(3).toList()

            result shouldBeEqualTo listOf(
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
                null,
                TelemetryTarget(SYSTEM_1, SYSTEM_1_ADDRESS),
            )
        }

    @Tag("REQ-023")
    @Test
    fun `GIVEN a round waiting for ACKs WHEN the last observer leaves THEN the socket closes on time all the same`() =
        testScope.runTest {
            mockkStatic(Log::class)
            every { Log.w(any(), any<String>()) } returns 0
            var closedAt: Long? = null
            val start = testScheduler.timeSource.markNow()
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
                .onCompletion { closedAt = start.elapsedNow().inWholeMilliseconds }
            val activeVehicle = ActiveVehicle()
            val session = sessionOn(transport, activeVehicle)
            val requester = TelemetryRequester(
                sender = GcsMessageSender(transport, ignoringRecorder(), session),
                session = session,
                store = telemetryStore,
                rule = StalenessRule(),
                timeSource = testScheduler.timeSource,
            )
            val requesting = repositoryOn(transport, requester, telemetryStore, activeVehicle, session)
            val observer = requesting.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)
            observer.cancel()

            delay(SESSION_STOPPED)

            closedAt shouldBeEqualTo SESSION_CLOSED_AFTER_OBSERVED_BRIEFLY
        }

    /**
     * Every HEARTBEAT this GCS sends, as (virtual ms since this was called, destination). Call it first.
     * While [isOpen] is false the transport answers [SendResult.NotOpen] and nothing is recorded, as a
     * closed socket sends nothing.
     */
    private fun TestScope.recordSends(isOpen: () -> Boolean = { true }): List<Pair<Long, InetSocketAddress>> {
        val sent = mutableListOf<Pair<Long, InetSocketAddress>>()
        val start = testScheduler.timeSource.markNow()
        coEvery { transport.send(any(), any()) } coAnswers {
            if (isOpen()) {
                sent += start.elapsedNow().inWholeMilliseconds to secondArg<InetSocketAddress>()
                SendResult.Sent
            } else {
                SendResult.NotOpen
            }
        }
        return sent
    }
}

/** The repository under test on [transport], in this scope's virtual time; each test passes only what it changes. */
// Mirrors the repository's constructor, one parameter each.
@Suppress("LongParameterList")
private fun TestScope.repositoryOn(
    transport: UdpTransport,
    telemetryRequester: TelemetryRequester,
    telemetryStore: TelemetryStore,
    activeVehicle: ActiveVehicle = ActiveVehicle(),
    session: LinkSession = sessionOn(transport, activeVehicle),
    timeSource: TimeSource.WithComparableMarks = testScheduler.timeSource,
    rule: LinkLossRule = LinkLossRule(),
    heartbeatSender: GcsHeartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder(), session)),
    scope: CoroutineScope = backgroundScope,
) = DefaultVehicleRepository(
    session = session,
    timeSource = timeSource,
    rule = rule,
    heartbeatSender = heartbeatSender,
    telemetryRequester = telemetryRequester,
    telemetryStore = telemetryStore,
    activeVehicle = activeVehicle,
    scope = scope,
)

/** A link session on [transport] in this scope's virtual time, recording nothing. */
private fun TestScope.sessionOn(transport: UdpTransport, activeVehicle: ActiveVehicle) = LinkSession(
    live = transport,
    scope = backgroundScope,
    timeSource = testScheduler.timeSource,
    pipelineClock = PipelineClock(testScheduler.timeSource),
    recorder = ignoringRecorder(),
    activeVehicle = activeVehicle,
)

private const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
private const val DISARMED_GUIDED = "heartbeat/disarmed-guided.bin"
private const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
private const val ARMED_GUIDED = "heartbeat/armed-guided.bin"
private const val SYSTEM_2_LOITER = "derived/heartbeat-sysid-2.bin"
private const val GCS = "gcs/heartbeat-mission-planner.bin"
private const val GIMBAL = "derived/heartbeat-gimbal-component.bin"

/** `time_boot_ms` 4000000000 and then 397119 (pymavlink): back by far more than the reboot backstep. */
private const val ATTITUDE_LONG_UP = "derived/attitude-edges.bin"
private const val ATTITUDE_AFTER_REBOOT = "telemetry/attitude.bin"

// Virtual milliseconds. The default timeout is 3 s (REQ-017).
private const val GAP = 100L
private const val AFTER_TWO_GAPS = 300L
private const val HALF_TIMEOUT = 1_500L
private const val JUST_UNDER_TIMEOUT = 2_900L
private const val WELL_PAST_TIMEOUT = 4_000L
private const val TIMEOUT_MILLIS = 3_000L
private const val HEARTBEAT_PERIOD = 1_000L

/** The first observer leaves after the first HEARTBEAT; the session then stops 5 s later (at 6.2 s). */
private const val OBSERVED_BRIEFLY = 1_200L

/** The observer leaves at 1.2 s and the session waits 5 s more: closed at 6.2 s, whoever else is listening. */
private const val SESSION_CLOSED_AFTER_OBSERVED_BRIEFLY = 6_200L

/** Longer than from the first HEARTBEAT (0.1 s) to the LOST (3.1 s) and the recovery (4.1 s). */
private const val BUSY_PAST_RECOVERY = 5_000L

/** From the observer leaving to 7.2 s: the session has stopped but the last HEARTBEAT (6 s) is not 3 s old. */
private const val UNTIL_SESSION_STOPPED_BEFORE_TIMEOUT = 6_000L

/** Past the second HEARTBEAT (4.1 s), which arrives after the watchdog failed at its first timeout (3.1 s). */
private const val AFTER_WATCHDOG_FAILED = 5_000L

/** Longer than the observer stop timeout (5 s) plus the link-loss timeout: the session has stopped. */
private const val SESSION_STOPPED = 10_000L
private const val FOUR_AND_A_HALF_SECONDS = 4_500L
private const val SEVEN_AND_A_HALF_SECONDS = 7_500L

/**
 * The socket fails at 0.1 s and reopens at 1.1 s: ticks at 2 and 3 s still see the vehicle CONNECTED (heard
 * at 0.1 s) with the socket open; it is LOST from 3.1 s until heard again at 5.1 s; then 6 and 7 s.
 */
private val SENT_AROUND_THE_OUTAGE = listOf(2_000L, 3_000L, 6_000L, 7_000L)
private val TIMEOUT = 3.seconds
private val CUSTOM_TIMEOUT = 500.milliseconds

private val SYSTEM_1_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
private val SYSTEM_2_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_561)
private val GCS_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_570)

/** HEARTBEATs sent by this GCS while the vehicle is CONNECTED, at each tick of the sender (virtual ms). */
private val SENT_WHILE_CONNECTED = listOf(1_000L, 2_000L, 3_000L)

/** The last tick before the session closes at 6.2 s (observed for 1.2 s, then the 5 s stop timeout). */
private const val LAST_TICK_BEFORE_SESSION_CLOSED = 6_000L
private const val ONE_SECOND = 1_000L
private const val ONE_AND_A_HALF_SECONDS = 1_500L

/**
 * The repository's clock, except that the second mark (the second HEARTBEAT's arrival) cannot be read:
 * the watchdog fails on it, while the vehicle in the map keeps the first, readable mark.
 */
private class FailsOnSecondMark(private val clock: TimeSource.WithComparableMarks) : TimeSource.WithComparableMarks {
    private var marks = 0

    override fun markNow(): ComparableTimeMark = if (++marks == 2) Unreadable else clock.markNow()

    private object Unreadable : ComparableTimeMark {
        override fun elapsedNow(): Duration = error("Unreadable test mark")

        override fun plus(duration: Duration): ComparableTimeMark = this

        override fun minus(other: ComparableTimeMark): Duration = error("Unreadable test mark")

        override fun equals(other: Any?): Boolean = other === this

        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

private val SYSTEM_1 = SystemId(1)
private val SYSTEM_2 = SystemId(2)
private val STABILIZE_DISARMED = copter(SYSTEM_1, "Stabilize", armed = false)
private val GUIDED_DISARMED = copter(SYSTEM_1, "Guided", armed = false)
private val LOITER_DISARMED = copter(SYSTEM_1, "Loiter", armed = false)
private val GUIDED_ARMED = copter(SYSTEM_1, "Guided", armed = true)
private val LOITER_DISARMED_2 = copter(SYSTEM_2, "Loiter", armed = false)

/** What an observer sees of a vehicle, without its time mark (which only the clock can produce). */
private data class Seen(val state: VehicleState, val link: VehicleLink)

private fun Map<SystemId, Vehicle>.seen(): Map<SystemId, Seen> = mapValues { (_, vehicle) ->
    Seen(vehicle.state, vehicle.link)
}

private fun Map<SystemId, Vehicle>.anyLost(): Boolean = values.any { it.link == VehicleLink.LOST }

private fun seen(vararg vehicles: Pair<SystemId, Seen>): Map<SystemId, Seen> = mapOf(*vehicles)

private fun connected(state: VehicleState) = Seen(state, VehicleLink.CONNECTED)

private fun lost(state: VehicleState) = Seen(state, VehicleLink.LOST)

private fun copter(systemId: SystemId, mode: String, armed: Boolean) = VehicleState(
    systemId = systemId,
    type = VehicleType.COPTER,
    flightMode = FlightMode.Named(mode),
    armed = armed,
)

/** The same HEARTBEAT once per second for as long as the session lasts, as a live vehicle sends it. */
private fun heartbeatEverySecond(fixture: String): Flow<Datagram> = flow {
    while (true) {
        delay(HEARTBEAT_PERIOD)
        emit(datagramOf(fixture))
    }
}

/**
 * One fixture per datagram, each after its gap in virtual milliseconds; then [afterwards] runs. By default
 * the socket stays open with nothing more arriving, as when the vehicle goes silent.
 */
private fun datagrams(
    vararg fixtures: Pair<Long, String>,
    afterwards: suspend FlowCollector<Datagram>.() -> Unit = { awaitCancellation() },
): Flow<Datagram> = flow {
    fixtures.forEach { (gap, fixture) ->
        delay(gap)
        emit(datagramOf(fixture))
    }
    afterwards()
}

/** [fixture] as the transport delivers it: system 2 and the ground station each send from their own address. */
private fun datagramOf(fixture: String) = Datagram(
    payload = readFixture(fixture),
    sender = when (fixture) {
        SYSTEM_2_LOITER -> SYSTEM_2_ADDRESS
        GCS -> GCS_ADDRESS
        else -> SYSTEM_1_ADDRESS
    },
)
