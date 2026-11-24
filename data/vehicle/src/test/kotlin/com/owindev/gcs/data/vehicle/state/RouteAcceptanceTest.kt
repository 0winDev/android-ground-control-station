package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.RouteAcceptance
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.RouteStatus
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.command.CommandRoute
import com.owindev.gcs.data.vehicle.command.keyedBy
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The operator accepting the active vehicle's new route (REQ-051), from real SITL HEARTBEATs through the pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-051")
class RouteAcceptanceTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val telemetryStore = TelemetryStore(TrailRule(StalenessRule()), ActiveVehicle())
    private val uplink = DefaultUplinkRepository(testScope.testScheduler.timeSource)
    private val announced = mutableListOf<VehicleTarget?>()
    private val routeRule = RouteRule(LinkLossRule())

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), any()) } returns SendResult.Sent
        coEvery { telemetryRequester.run(any(), any()) } coAnswers {
            firstArg<Flow<VehicleTarget?>>().collect { announced += it }
        }
        repository = testScope.repositoryOn(transport, telemetryRequester, telemetryStore, uplink = uplink)
    }

    @Test
    fun `GIVEN the route changed WHEN reading it THEN shows the first and the new address on the current key`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedForGood()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)

            val result = repository.route.value

            result?.key shouldBeEqualTo repository.currentKey
            result?.pinned shouldBeEqualTo SYSTEM_1_ADDRESS.toRoute()
            result?.heard?.keys shouldBeEqualTo setOf(SYSTEM_1_ADDRESS.toRoute(), MOVED_ADDRESS.toRoute())
        }

    @Test
    fun `GIVEN the route changed WHEN the operator accepts it THEN is accepted`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.acceptRoute(offerOnCurrentKey()) }
        val result = deferred.await()

        result shouldBeEqualTo RouteAcceptance.Accepted
    }

    @Test
    fun `GIVEN an accepted route WHEN routing a command THEN goes to the new address`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)
        repository.acceptRoute(offerOnCurrentKey())

        val result = repository.routeTo(SYSTEM_1)

        result shouldBeEqualTo CommandRoute.Open(MOVED_ADDRESS)
    }

    @Test
    fun `GIVEN a slide on the key before an accepted route WHEN routing on it THEN nothing goes, as changed`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedForGood()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            val oldKey = requireNotNull(repository.currentKey)
            repository.acceptRoute(offerOnCurrentKey())

            val result = repository.keyedBy(oldKey).routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Closed(NotSentReason.KeyChanged)
        }

    @Test
    fun `GIVEN an offer on a key no longer current WHEN accepting it THEN is outdated`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)
        val current = offerOnCurrentKey()
        val outdated = current.copy(key = current.key.copy(connection = current.key.connection - 1))

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.acceptRoute(outdated) }
        val result = deferred.await()

        result shouldBeEqualTo RouteAcceptance.Outdated
    }

    @Test
    fun `GIVEN the route changed and then the vehicle went LOST WHEN accepting it THEN is outdated`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    repeat(MOVED_HEARTBEATS) {
                        delay(ONE_SECOND)
                        emit(movedHeartbeat())
                    }
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            val offer = offerOnCurrentKey()
            delay(WELL_PAST_TIMEOUT)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.acceptRoute(offer) }
            val result = deferred.await()

            result shouldBeEqualTo RouteAcceptance.Outdated
        }

    @Test
    fun `GIVEN an outdated offer refused WHEN routing a command THEN it is still held`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)
        val current = offerOnCurrentKey()
        repository.acceptRoute(current.copy(key = current.key.copy(connection = current.key.connection - 1)))

        val result = repository.routeTo(SYSTEM_1)

        result shouldBeEqualTo CommandRoute.Closed(NotSentReason.RouteChanged)
    }

    @Test
    fun `GIVEN the HEARTBEATs alternate between two addresses WHEN accepting the second THEN is outdated`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    while (true) {
                        delay(HALF_A_SECOND)
                        emit(movedHeartbeat())
                        delay(HALF_A_SECOND)
                        emit(datagramOf(DISARMED_STABILIZE))
                    }
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.acceptRoute(offerOnCurrentKey()) }
            val result = deferred.await()

            result shouldBeEqualTo RouteAcceptance.Outdated
        }

    @Test
    fun `GIVEN an accepted route WHEN the HEARTBEATs move again for good THEN it changed from the accepted one`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedAndThenThird()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            repository.acceptRoute(offerOnCurrentKey())
            delay(UNTIL_THIRD_ALONE)

            val result = repository.route.value?.let(routeRule::statusOf)

            result shouldBeEqualTo RouteStatus.Changed(from = MOVED_ADDRESS.toRoute(), to = THIRD_ADDRESS.toRoute())
        }

    @Test
    fun `GIVEN an accepted route and the HEARTBEATs moved again WHEN routing THEN commands are held`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedAndThenThird()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            repository.acceptRoute(offerOnCurrentKey())
            delay(UNTIL_THIRD_ALONE)

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Closed(NotSentReason.RouteChanged)
        }

    @Test
    fun `GIVEN a second change accepted on its own key WHEN routing THEN goes to the third address`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedAndThenThird()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            repository.acceptRoute(offerOnCurrentKey())
            delay(UNTIL_THIRD_ALONE)
            repository.acceptRoute(
                RouteOffer(requireNotNull(repository.currentKey), MOVED_ADDRESS.toRoute(), THIRD_ADDRESS.toRoute()),
            )

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Open(THIRD_ADDRESS)
        }

    @Test
    fun `GIVEN the route changed to another address since the offer WHEN accepting it THEN is outdated`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    repeat(MOVED_HEARTBEATS - 1) {
                        delay(ONE_SECOND)
                        emit(movedHeartbeat())
                    }
                    while (true) {
                        delay(ONE_SECOND)
                        emit(Datagram(readFixture(DISARMED_STABILIZE), THIRD_ADDRESS))
                    }
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            val offer = offerOnCurrentKey()
            delay(UNTIL_THIRD_ALONE)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                repository.acceptRoute(offer.copy(key = requireNotNull(repository.currentKey)))
            }
            val result = deferred.await()

            result shouldBeEqualTo RouteAcceptance.Outdated
        }

    @Test
    fun `GIVEN an accepted route WHEN a new session starts THEN its first HEARTBEAT pins the route again`() =
        testScope.runTest {
            val activeVehicle = ActiveVehicle()
            val session = sessionOn(transport, activeVehicle)
            val switching = repositoryOn(transport, telemetryRequester, telemetryStore, activeVehicle, session)
            every { transport.datagrams() } returnsMany listOf(movedForGood(), datagrams(GAP to DISARMED_STABILIZE))
            switching.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            switching.acceptRoute(offerOn(switching))
            session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
            runCurrent()
            session.live()
            delay(AFTER_TWO_GAPS)

            val result = switching.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Open(SYSTEM_1_ADDRESS)
        }

    @Test
    fun `GIVEN an accepted route WHEN reading the last request target THEN is the new address`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)
        repository.acceptRoute(offerOnCurrentKey())

        val result = announced.last()

        result shouldBeEqualTo VehicleTarget(SYSTEM_1, MOVED_ADDRESS)
    }

    @Tag("REQ-045")
    @Tag("REQ-048")
    @Test
    fun `GIVEN an answer by the first route and the new route accepted WHEN reading the uplink THEN awaits one`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedForGood()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            uplink.commandAnswered(SYSTEM_1_ADDRESS)
            repository.acceptRoute(offerOnCurrentKey())

            val result = uplink.health.value.awaitsAnswer

            result shouldBeEqualTo true
        }

    @Tag("REQ-045")
    @Tag("REQ-048")
    @Test
    fun `GIVEN an accepted route and an answer by it WHEN reading the uplink THEN awaits none`() = testScope.runTest {
        every { transport.datagrams() } returns movedForGood()
        repository.vehicles.launchIn(backgroundScope)
        delay(CHANGED)
        repository.acceptRoute(offerOnCurrentKey())
        uplink.commandAnswered(MOVED_ADDRESS)

        val result = uplink.health.value.awaitsAnswer

        result shouldBeEqualTo false
    }

    @Tag("REQ-045")
    @Tag("REQ-048")
    @Test
    fun `GIVEN an accepted route and an answer by the first one WHEN reading the uplink THEN awaits one`() =
        testScope.runTest {
            every { transport.datagrams() } returns movedForGood()
            repository.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            repository.acceptRoute(offerOnCurrentKey())
            uplink.commandAnswered(SYSTEM_1_ADDRESS)

            val result = uplink.health.value.awaitsAnswer

            result shouldBeEqualTo true
        }

    @Tag("REQ-045")
    @Test
    fun `GIVEN an accepted route and a new session from another address WHEN answered by it THEN awaits none`() =
        testScope.runTest {
            val activeVehicle = ActiveVehicle()
            val session = sessionOn(transport, activeVehicle)
            val switching =
                repositoryOn(transport, telemetryRequester, telemetryStore, activeVehicle, session, uplink = uplink)
            every { transport.datagrams() } returnsMany listOf(
                movedForGood(),
                flow {
                    delay(GAP)
                    emit(Datagram(readFixture(DISARMED_STABILIZE), THIRD_ADDRESS))
                    awaitCancellation()
                },
            )
            switching.vehicles.launchIn(backgroundScope)
            delay(CHANGED)
            switching.acceptRoute(offerOn(switching))
            session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
            runCurrent()
            session.live()
            delay(AFTER_TWO_GAPS)
            uplink.commandAnswered(THIRD_ADDRESS)

            val result = uplink.health.value.awaitsAnswer

            result shouldBeEqualTo false
        }

    @Test
    fun `GIVEN HEARTBEATs from a flood of made-up addresses WHEN judging the route THEN alternates on three kept`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    repeat(FLOOD) { index ->
                        delay(1)
                        emit(Datagram(readFixture(DISARMED_STABILIZE), madeUp(index)))
                    }
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(GAP + FLOOD + GAP)

            val result = repository.route.value

            result?.heard?.size shouldBeEqualTo KEPT
            result?.let(routeRule::statusOf) shouldBeEqualTo RouteStatus.Alternating(
                SYSTEM_1_ADDRESS.toRoute(),
                listOf(madeUp(FLOOD - 1), madeUp(FLOOD - 2), SYSTEM_1_ADDRESS).map { it.toRoute() },
            )
        }

    @Tag("REQ-045")
    @Tag("REQ-048")
    @Test
    fun `GIVEN the first HEARTBEAT and an answer by its address WHEN reading the uplink THEN awaits none`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)
            uplink.commandAnswered(SYSTEM_1_ADDRESS)

            val result = uplink.health.value.awaitsAnswer

            result shouldBeEqualTo false
        }

    @Tag("REQ-045")
    @Tag("REQ-048")
    @Test
    fun `GIVEN the first HEARTBEAT and an answer by another address WHEN reading the uplink THEN awaits one`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)
            uplink.commandAnswered(MOVED_ADDRESS)

            val result = uplink.health.value.awaitsAnswer

            result shouldBeEqualTo true
        }

    @Test
    fun `GIVEN HEARTBEATs from many made-up addresses WHEN reading the route THEN keeps the first and two newest`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    repeat(MADE_UP_ADDRESSES) { index ->
                        delay(ONE_SECOND)
                        emit(Datagram(readFixture(DISARMED_STABILIZE), madeUp(index)))
                    }
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_MADE_UP_ADDRESSES)

            val result = repository.route.value?.heard?.keys

            result shouldBeEqualTo setOf(SYSTEM_1_ADDRESS, madeUp(8), madeUp(9)).map { it.toRoute() }.toSet()
        }

    private fun offerOnCurrentKey() = offerOn(repository)

    private fun offerOn(repository: DefaultVehicleRepository) = RouteOffer(
        key = requireNotNull(repository.currentKey),
        from = SYSTEM_1_ADDRESS.toRoute(),
        to = MOVED_ADDRESS.toRoute(),
    )
}

/** The first HEARTBEAT from the vehicle's address, then every second from [MOVED_ADDRESS] only. */
private fun movedForGood(): Flow<Datagram> = datagrams(
    GAP to DISARMED_STABILIZE,
    afterwards = {
        while (true) {
            delay(ONE_SECOND)
            emit(movedHeartbeat())
        }
    },
)

/** [movedForGood] for [MOVED_HEARTBEATS] seconds, then every second from [THIRD_ADDRESS] only. */
private fun movedAndThenThird(): Flow<Datagram> = datagrams(
    GAP to DISARMED_STABILIZE,
    afterwards = {
        repeat(MOVED_HEARTBEATS) {
            delay(ONE_SECOND)
            emit(movedHeartbeat())
        }
        while (true) {
            delay(ONE_SECOND)
            emit(Datagram(readFixture(DISARMED_STABILIZE), THIRD_ADDRESS))
        }
    },
)

private fun movedHeartbeat() = Datagram(readFixture(DISARMED_STABILIZE), MOVED_ADDRESS)

private fun madeUp(index: Int) = InetSocketAddress(InetAddress.getLoopbackAddress(), FIRST_MADE_UP_PORT + index)

// Virtual milliseconds. The first address is last heard at 0.1 s, the moved one from 1.1 s every second: from 3.1 s on
// only the moved one is heard within a timeout of the newest HEARTBEAT.
private const val ONE_SECOND = 1_000L
private const val HALF_A_SECOND = 500L
private const val CHANGED = 4_500L
private const val MOVED_HEARTBEATS = 5

// The moved address is last heard at 5.1 s, the third one from 6.1 s: alone from 8.1 s on.
private const val UNTIL_THIRD_ALONE = 4_000L
private const val FLOOD = 1_000
private const val KEPT = 3
private const val MADE_UP_ADDRESSES = 10
private const val AFTER_MADE_UP_ADDRESSES = 10_500L
private const val FIRST_MADE_UP_PORT = 15_000
private val MOVED_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_580)
private val THIRD_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_590)
