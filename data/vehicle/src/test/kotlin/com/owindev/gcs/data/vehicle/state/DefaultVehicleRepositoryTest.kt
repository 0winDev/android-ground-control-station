package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.LinkSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real ArduCopter SITL HEARTBEATs through the whole pipeline (transport → parser → decoder → mapper).
 * Expected values come from pymavlink and the ArduCopter docs (`src/test/resources/README.md`).
 */
class DefaultVehicleRepositoryTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        val session = LinkSession(transport = transport, scope = testScope.backgroundScope)
        repository = DefaultVehicleRepository(session = session, scope = testScope.backgroundScope)
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN real SITL HEARTBEATs WHEN observing the vehicles THEN follows the mode and armed state`() =
        testScope.runTest {
            every { transport.datagrams() } returns fixturesArriving(
                DISARMED_STABILIZE,
                DISARMED_GUIDED,
                DISARMED_LOITER,
                ARMED_GUIDED,
            )

            val result = repository.vehicles.take(5).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                vehicles(copter(SYSTEM_1, STABILIZE, armed = false)),
                vehicles(copter(SYSTEM_1, GUIDED, armed = false)),
                vehicles(copter(SYSTEM_1, LOITER, armed = false)),
                vehicles(copter(SYSTEM_1, GUIDED, armed = true)),
            )
        }

    @Tag("REQ-016")
    @Test
    fun `GIVEN HEARTBEATs from two systems WHEN observing the vehicles THEN keeps each one apart`() =
        testScope.runTest {
            every { transport.datagrams() } returns fixturesArriving(DISARMED_LOITER, SYSTEM_2_LOITER, ARMED_GUIDED)

            val result = repository.vehicles.take(4).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                vehicles(copter(SYSTEM_1, LOITER, armed = false)),
                vehicles(copter(SYSTEM_1, LOITER, armed = false), copter(SYSTEM_2, LOITER, armed = false)),
                vehicles(copter(SYSTEM_1, GUIDED, armed = true), copter(SYSTEM_2, LOITER, armed = false)),
            )
        }

    @Tag("REQ-016")
    @Test
    fun `GIVEN a ground station HEARTBEAT between the vehicle ones WHEN observing the vehicles THEN is ignored`() =
        testScope.runTest {
            every { transport.datagrams() } returns fixturesArriving(DISARMED_STABILIZE, GCS, DISARMED_GUIDED)

            val result = repository.vehicles.take(3).toList()

            result shouldBeEqualTo listOf(
                emptyMap(),
                vehicles(copter(SYSTEM_1, STABILIZE, armed = false)),
                vehicles(copter(SYSTEM_1, GUIDED, armed = false)),
            )
        }
}

private const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
private const val DISARMED_GUIDED = "heartbeat/disarmed-guided.bin"
private const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
private const val ARMED_GUIDED = "heartbeat/armed-guided.bin"
private const val SYSTEM_2_LOITER = "derived/heartbeat-sysid-2.bin"
private const val GCS = "gcs/heartbeat-mission-planner.bin"
private const val GAP_MILLIS = 100L
private val SYSTEM_1 = SystemId(1)
private val SYSTEM_2 = SystemId(2)
private val STABILIZE = FlightMode.Named("Stabilize")
private val GUIDED = FlightMode.Named("Guided")
private val LOITER = FlightMode.Named("Loiter")

/** One fixture per datagram, spaced in virtual time so that every state reaches the observer. */
private fun fixturesArriving(vararg fixtures: String): Flow<ByteArray> = flow {
    fixtures.forEach { fixture ->
        delay(GAP_MILLIS)
        emit(readFixture(fixture))
    }
}

private fun copter(systemId: SystemId, flightMode: FlightMode, armed: Boolean) = VehicleState(
    systemId = systemId,
    type = VehicleType.COPTER,
    flightMode = flightMode,
    armed = armed,
)

private fun vehicles(vararg states: VehicleState): Map<SystemId, VehicleState> = states.associateBy { it.systemId }
