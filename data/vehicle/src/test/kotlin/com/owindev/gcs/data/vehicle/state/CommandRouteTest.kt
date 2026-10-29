package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.command.CommandRoute
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Where [DefaultVehicleRepository] lets a command go, from real SITL HEARTBEATs through the whole pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouteTest {

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

    @Tag("REQ-043")
    @Test
    fun `GIVEN the active vehicle is heard WHEN routing a command to it THEN goes to its first HEARTBEAT's address`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Open(SYSTEM_1_ADDRESS)
        }

    @Tag("REQ-043")
    @Test
    fun `GIVEN the active vehicle's HEARTBEATs move to another address WHEN routing THEN the route is changed`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    delay(GAP)
                    emit(Datagram(readFixture(DISARMED_STABILIZE), MOVED_ADDRESS))
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Closed(NotSentReason.ROUTE_CHANGED)
        }

    @Tag("REQ-043")
    @Test
    fun `GIVEN the active vehicle's HEARTBEATs come back to its first address WHEN routing THEN the route is open`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    delay(GAP)
                    emit(Datagram(readFixture(DISARMED_STABILIZE), MOVED_ADDRESS))
                    delay(GAP)
                    emit(datagramOf(DISARMED_STABILIZE))
                    awaitCancellation()
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_THREE_GAPS)

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Open(SYSTEM_1_ADDRESS)
        }

    @Tag("REQ-043")
    @Test
    fun `GIVEN the HEARTBEATs stay on another address WHEN routing long after THEN the route stays changed`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                afterwards = {
                    while (true) {
                        delay(ONE_SECOND)
                        emit(Datagram(readFixture(DISARMED_STABILIZE), MOVED_ADDRESS))
                    }
                },
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(TEN_SECONDS)

            val result = repository.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Closed(NotSentReason.ROUTE_CHANGED)
        }

    @Tag("REQ-043")
    @Tag("REQ-037")
    @Test
    fun `GIVEN the vehicle comes back on another address after a replay WHEN routing THEN goes to the new address`() =
        testScope.runTest {
            val activeVehicle = ActiveVehicle()
            val session = sessionOn(transport, activeVehicle)
            val switching = repositoryOn(transport, telemetryRequester, telemetryStore, activeVehicle, session)
            every { transport.datagrams() } returnsMany listOf(
                datagrams(GAP to DISARMED_STABILIZE),
                flow {
                    delay(GAP)
                    emit(Datagram(readFixture(DISARMED_STABILIZE), MOVED_ADDRESS))
                    awaitCancellation()
                },
            )
            switching.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)
            session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
            runCurrent()
            session.live()
            delay(AFTER_TWO_GAPS)

            val result = switching.routeTo(SYSTEM_1)

            result shouldBeEqualTo CommandRoute.Open(MOVED_ADDRESS)
        }

    @Tag("REQ-043")
    @Tag("REQ-020")
    @Test
    fun `GIVEN another vehicle is heard too WHEN routing a command to it THEN it is not the active vehicle`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(GAP to DISARMED_LOITER, GAP to SYSTEM_2_LOITER)
            repository.vehicles.launchIn(backgroundScope)
            delay(AFTER_TWO_GAPS)

            val result = repository.routeTo(SYSTEM_2)

            result shouldBeEqualTo CommandRoute.Closed(NotSentReason.NOT_ACTIVE_VEHICLE)
        }

    @Tag("REQ-043")
    @Test
    fun `GIVEN the active vehicle is LOST WHEN routing a command to it THEN the link is lost`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(GAP to DISARMED_STABILIZE)
        repository.vehicles.launchIn(backgroundScope)
        delay(WELL_PAST_TIMEOUT)

        val result = repository.routeTo(SYSTEM_1)

        result shouldBeEqualTo CommandRoute.Closed(NotSentReason.LINK_LOST)
    }

    @Tag("REQ-043")
    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay plays WHEN routing a command THEN nothing goes to any vehicle`() = testScope.runTest {
        val replaying = repositoryOn(
            transport,
            telemetryRequester,
            telemetryStore,
            session = sessionOn(MutableStateFlow(REPLAY_SESSION)),
        )

        val result = replaying.routeTo(SYSTEM_1)

        result shouldBeEqualTo CommandRoute.Closed(NotSentReason.REPLAY)
    }
}

private const val AFTER_THREE_GAPS = 400L
private const val ONE_SECOND = 1_000L
private const val TEN_SECONDS = 10_000L
private val MOVED_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_580)

private fun emptySource(): DatagramSource = object : DatagramSource {
    override fun datagrams(): Flow<Datagram> = emptyFlow()
}
