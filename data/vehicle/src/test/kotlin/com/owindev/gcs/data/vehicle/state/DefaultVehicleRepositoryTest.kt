package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.LinkSession
import io.mockk.every
import io.mockk.mockk
import java.net.SocketException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real ArduCopter SITL HEARTBEATs through the whole pipeline (transport → parser → decoder → mapper →
 * watchdog), in virtual time: the repository's clock is the test scheduler's, so no test sleeps for real.
 * Expected values come from pymavlink and the ArduCopter docs (`src/test/resources/README.md`).
 */
class DefaultVehicleRepositoryTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        val session = LinkSession(transport = transport, scope = testScope.backgroundScope)
        repository = DefaultVehicleRepository(
            session = session,
            timeSource = testScope.testScheduler.timeSource,
            rule = LinkLossRule(),
            scope = testScope.backgroundScope,
        )
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
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE) { throw SocketException() }

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
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE) { }

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
     * (LOST then CONNECTED, or CONNECTED throughout, since the vehicle was heard in time). Here the HEARTBEAT
     * is handled first and the timer that follows is re-checked against it, so it never marks LOST.
     */
    @Tag("REQ-017")
    @Test
    fun `GIVEN a HEARTBEAT exactly at the timeout WHEN observing the vehicles THEN the stale timer marks no LOST`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                TIMEOUT_MILLIS to DISARMED_GUIDED,
            )

            val result = repository.vehicles.map { it.seen() }.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                seen(SYSTEM_1 to connected(STABILIZE_DISARMED)),
                seen(SYSTEM_1 to connected(GUIDED_DISARMED)),
                seen(SYSTEM_1 to lost(GUIDED_DISARMED)),
            )
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a custom timeout WHEN the vehicle goes silent THEN is LOST after that timeout`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
        val custom = DefaultVehicleRepository(
            session = LinkSession(transport = transport, scope = backgroundScope),
            timeSource = testScheduler.timeSource,
            rule = LinkLossRule(timeout = CUSTOM_TIMEOUT),
            scope = backgroundScope,
        )

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
            val failingRule: LinkLossRule = mockk()
            every { failingRule.timeout } returns TIMEOUT
            every { failingRule.linkAfter(any()) } returns VehicleLink.CONNECTED andThenThrows IllegalStateException()
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                WELL_PAST_TIMEOUT to DISARMED_GUIDED,
            )
            val failing = DefaultVehicleRepository(
                session = LinkSession(transport = transport, scope = backgroundScope),
                timeSource = testScheduler.timeSource,
                rule = failingRule,
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
}

private const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
private const val DISARMED_GUIDED = "heartbeat/disarmed-guided.bin"
private const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
private const val ARMED_GUIDED = "heartbeat/armed-guided.bin"
private const val SYSTEM_2_LOITER = "derived/heartbeat-sysid-2.bin"
private const val GCS = "gcs/heartbeat-mission-planner.bin"
private const val GIMBAL = "derived/heartbeat-gimbal-component.bin"

// Virtual milliseconds. The default timeout is 3 s (REQ-017).
private const val GAP = 100L
private const val HALF_TIMEOUT = 1_500L
private const val JUST_UNDER_TIMEOUT = 2_900L
private const val WELL_PAST_TIMEOUT = 4_000L
private const val TIMEOUT_MILLIS = 3_000L
private const val HEARTBEAT_PERIOD = 1_000L

/** The first observer leaves after the first HEARTBEAT; the session then stops 5 s later (at 6.2 s). */
private const val OBSERVED_BRIEFLY = 1_200L

/** From the observer leaving to 7.2 s: the session has stopped but the last HEARTBEAT (6 s) is not 3 s old. */
private const val UNTIL_SESSION_STOPPED_BEFORE_TIMEOUT = 6_000L

/** Past the second HEARTBEAT (4.1 s), which arrives after the watchdog failed at its first timeout (3.1 s). */
private const val AFTER_WATCHDOG_FAILED = 5_000L

/** Longer than the observer stop timeout (5 s) plus the link-loss timeout: the session has stopped. */
private const val SESSION_STOPPED = 10_000L
private val TIMEOUT = 3.seconds
private val CUSTOM_TIMEOUT = 500.milliseconds

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
private fun heartbeatEverySecond(fixture: String): Flow<ByteArray> = flow {
    while (true) {
        delay(HEARTBEAT_PERIOD)
        emit(readFixture(fixture))
    }
}

/**
 * One fixture per datagram, each after its gap in virtual milliseconds; then [afterwards] runs. By default
 * the socket stays open with nothing more arriving, as when the vehicle goes silent.
 */
private fun datagrams(
    vararg fixtures: Pair<Long, String>,
    afterwards: suspend FlowCollector<ByteArray>.() -> Unit = { awaitCancellation() },
): Flow<ByteArray> = flow {
    fixtures.forEach { (gap, fixture) ->
        delay(gap)
        emit(readFixture(fixture))
    }
    afterwards()
}
