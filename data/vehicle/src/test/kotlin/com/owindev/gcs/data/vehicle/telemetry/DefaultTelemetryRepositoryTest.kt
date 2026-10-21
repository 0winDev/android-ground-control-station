package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The vehicles are a hand-fed contract; the store is the real one. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTelemetryRepositoryTest {

    private val testScope = TestScope()
    private val vehicleRepository: VehicleRepository = mockk()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val store = TelemetryStore(TrailRule(StalenessRule()), ActiveVehicle())
    private val session = MutableStateFlow(FIRST_SESSION)

    private lateinit var repository: DefaultTelemetryRepository

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns vehicles
        every { vehicleRepository.activeSystemId } returns activeSystemId
        repository = DefaultTelemetryRepository(store, vehicleRepository, sessionOn(session), testScope.backgroundScope)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN live telemetry WHEN a replay starts before the store moves on THEN none of it is shown`() =
        testScope.runTest {
            store.record(received(SYSTEM_1), RECEIVED_AT)
            activeSystemId.value = SystemId(SYSTEM_1)
            repository.activeTelemetry.launchIn(backgroundScope)
            runCurrent()

            session.value = REPLAY_SESSION
            runCurrent()
            val result = repository.activeTelemetry.value

            result shouldBeEqualTo Telemetry(origin = DataOrigin.REPLAY)
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN telemetry but no active vehicle WHEN observing the active telemetry THEN there is none`() =
        testScope.runTest {
            store.record(received(SYSTEM_1), RECEIVED_AT)
            repository.activeTelemetry.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTelemetry.value

            result.shouldBeNull()
        }

    @Tag("REQ-026")
    @Test
    fun `GIVEN an active vehicle with no telemetry yet WHEN observing it THEN every part is empty`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            repository.activeTelemetry.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTelemetry.value

            result shouldBeEqualTo Telemetry(origin = DataOrigin.LIVE)
        }

    @Tag("REQ-016")
    @Tag("REQ-020")
    @Test
    fun `GIVEN telemetry from two systems WHEN observing the active telemetry THEN is only the active vehicle's`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            store.record(received(SYSTEM_1), RECEIVED_AT)
            store.record(received(SYSTEM_2, roll = HALF_PI), RECEIVED_AT)
            repository.activeTelemetry.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTelemetry.value

            result shouldBeEqualTo Telemetry(attitude = Timed(LEVEL, RECEIVED_AT), origin = DataOrigin.LIVE)
        }

    @Tag("REQ-026")
    @Test
    fun `GIVEN the active telemetry observed WHEN new telemetry arrives THEN follows it`() = testScope.runTest {
        activeSystemId.value = SystemId(SYSTEM_1)
        repository.activeTelemetry.launchIn(backgroundScope)
        runCurrent()

        store.record(received(SYSTEM_1), RECEIVED_AT)
        runCurrent()
        val result = repository.activeTelemetry.value

        result shouldBeEqualTo Telemetry(attitude = Timed(LEVEL, RECEIVED_AT), origin = DataOrigin.LIVE)
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN the active telemetry observed WHEN reading the vehicles THEN are observed too, keeping the link open`() =
        testScope.runTest {
            repository.activeTelemetry.launchIn(backgroundScope)

            runCurrent()
            val result = vehicles.subscriptionCount.value

            result shouldBeEqualTo 1
        }

    @Tag("REQ-026")
    @Test
    fun `GIVEN the active telemetry no longer observed WHEN reading it THEN its last value is gone`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            store.record(received(SYSTEM_1), RECEIVED_AT)
            val observer = repository.activeTelemetry.launchIn(backgroundScope)
            runCurrent()

            observer.cancel()
            runCurrent()
            val result = repository.activeTelemetry.value

            result.shouldBeNull()
        }

    @Tag("REQ-033")
    @Test
    fun `GIVEN an active vehicle heard WHEN no HOME_POSITION has arrived THEN its home is unknown`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            store.record(received(SYSTEM_1), RECEIVED_AT)
            repository.activeTelemetry.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTelemetry.value?.home

            result.shouldBeNull()
        }

    @Tag("REQ-016")
    @Tag("REQ-033")
    @Test
    fun `GIVEN the active vehicle's home known WHEN another vehicle without one becomes active THEN home is unknown`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            store.record(home(SYSTEM_1, HOME_LATITUDE_DEGE7), RECEIVED_AT)
            store.record(received(SYSTEM_2), RECEIVED_AT)
            repository.activeTelemetry.launchIn(backgroundScope)
            runCurrent()

            activeSystemId.value = SystemId(SYSTEM_2)
            runCurrent()
            val result = repository.activeTelemetry.value

            result shouldBeEqualTo Telemetry(attitude = Timed(LEVEL, RECEIVED_AT), origin = DataOrigin.LIVE)
        }

    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN the vehicle moves it THEN home follows it`() = testScope.runTest {
        activeSystemId.value = SystemId(SYSTEM_1)
        store.record(home(SYSTEM_1, HOME_LATITUDE_DEGE7), RECEIVED_AT)
        repository.activeTelemetry.launchIn(backgroundScope)
        runCurrent()

        store.record(home(SYSTEM_1, MOVED_LATITUDE_DEGE7), MOVED_AT)
        runCurrent()
        val result = repository.activeTelemetry.value?.home

        result shouldBeEqualTo Timed(MOVED_HOME, MOVED_AT)
    }

    @Tag("REQ-031")
    @Tag("REQ-033")
    @Test
    fun `GIVEN a known home WHEN a HOME_POSITION past the pole arrives THEN keeps the last home`() = testScope.runTest {
        activeSystemId.value = SystemId(SYSTEM_1)
        store.record(home(SYSTEM_1, HOME_LATITUDE_DEGE7), RECEIVED_AT)
        repository.activeTelemetry.launchIn(backgroundScope)
        runCurrent()

        store.record(home(SYSTEM_1, PAST_NORTH_POLE_DEGE7), MOVED_AT)
        runCurrent()
        val result = repository.activeTelemetry.value?.home

        result shouldBeEqualTo Timed(HOME, RECEIVED_AT)
    }
}

private const val SYSTEM_1 = 1
private const val SYSTEM_2 = 2
private const val MAV_COMP_ID_AUTOPILOT1 = 1
private const val HALF_PI = (Math.PI / 2).toFloat()

private const val HOME_LATITUDE_DEGE7 = -350_000_000
private const val MOVED_LATITUDE_DEGE7 = -349_990_000
private const val PAST_NORTH_POLE_DEGE7 = 900_000_001

private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
private val CLOCK = TestTimeSource()
private val RECEIVED_AT = CLOCK.markNow()
private val MOVED_AT = CLOCK.markNow()
private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)
private val HOME = Home(latitude = -35.0, longitude = 149.0, altitudeMsl = 584.0)
private val MOVED_HOME = HOME.copy(latitude = -34.999)

private fun received(systemId: Int, roll: Float = 0f) = ReceivedMessage(
    systemId = systemId,
    componentId = MAV_COMP_ID_AUTOPILOT1,
    message = MavlinkMessage.Attitude(
        timeBootMs = 397_119,
        roll = roll,
        pitch = 0f,
        yaw = 0f,
        rollspeed = 0f,
        pitchspeed = 0f,
        yawspeed = 0f,
    ),
    sender = SENDER,
    session = FIRST_SESSION,
)

private fun home(systemId: Int, latitude: Int) = ReceivedMessage(
    systemId = systemId,
    componentId = MAV_COMP_ID_AUTOPILOT1,
    message = MavlinkMessage.HomePosition(
        latitude = latitude,
        longitude = 1_490_000_000,
        altitude = 584_000,
        x = 0f,
        y = 0f,
        z = 0f,
        q1 = Float.NaN,
        q2 = Float.NaN,
        q3 = Float.NaN,
        q4 = Float.NaN,
        approachX = 0f,
        approachY = 0f,
        approachZ = 0f,
        timeUsec = 443_979_837,
    ),
    sender = SENDER,
    session = FIRST_SESSION,
)
