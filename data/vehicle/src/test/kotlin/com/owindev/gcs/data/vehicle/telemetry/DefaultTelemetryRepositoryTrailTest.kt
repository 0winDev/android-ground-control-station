package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Trail
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.link.sessionOn
import io.mockk.every
import io.mockk.mockk
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
@Tag("REQ-035")
class DefaultTelemetryRepositoryTrailTest {

    private val testScope = TestScope()
    private val clock = TestTimeSource()
    private val vehicleRepository: VehicleRepository = mockk()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val store = TelemetryStore(TrailRule(StalenessRule()))
    private val session = MutableStateFlow(FIRST_SESSION)

    private lateinit var repository: DefaultTelemetryRepository

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns vehicles
        every { vehicleRepository.activeSystemId } returns activeSystemId
        repository = DefaultTelemetryRepository(store, vehicleRepository, sessionOn(session), testScope.backgroundScope)
    }

    @Tag("REQ-016")
    @Tag("REQ-020")
    @Test
    fun `GIVEN trails of two systems WHEN observing the active trail THEN is only the active vehicle's`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            flyTo(SYSTEM_1, step = 0)
            flyTo(SYSTEM_2, step = 5)
            repository.activeTrail.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTrail.value

            result shouldBeEqualTo Trail(listOf(listOf(trailPoint(0))), open = true)
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a trail but no active vehicle WHEN observing the active trail THEN there is none`() = testScope.runTest {
        flyTo(SYSTEM_1, step = 0)
        repository.activeTrail.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeTrail.value

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN an active vehicle with no verified position yet WHEN observing its trail THEN is empty`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            repository.activeTrail.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeTrail.value

            result shouldBeEqualTo Trail.EMPTY
        }

    @Test
    fun `GIVEN the active trail observed WHEN the vehicle moves on THEN follows it`() = testScope.runTest {
        activeSystemId.value = SystemId(SYSTEM_1)
        flyTo(SYSTEM_1, step = 0)
        repository.activeTrail.launchIn(backgroundScope)
        runCurrent()

        record(SYSTEM_1, trailPosition(step = 1))
        runCurrent()
        val result = repository.activeTrail.value

        result shouldBeEqualTo Trail(listOf(listOf(trailPoint(0), trailPoint(1))), open = true)
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a live trail WHEN a replay starts before the store moves on THEN none of it is shown`() =
        testScope.runTest {
            activeSystemId.value = SystemId(SYSTEM_1)
            flyTo(SYSTEM_1, step = 0)
            repository.activeTrail.launchIn(backgroundScope)
            runCurrent()

            session.value = REPLAY_SESSION
            runCurrent()
            val result = repository.activeTrail.value

            result shouldBeEqualTo Trail.EMPTY
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay's trail WHEN the next replay starts before the store moves on THEN none of it is shown`() =
        testScope.runTest {
            store.startSession(REPLAY_SESSION)
            session.value = REPLAY_SESSION
            activeSystemId.value = SystemId(SYSTEM_1)
            flyTo(SYSTEM_1, step = 0, REPLAY_SESSION)
            repository.activeTrail.launchIn(backgroundScope)
            runCurrent()

            session.value = NEXT_REPLAY_SESSION
            runCurrent()
            val result = repository.activeTrail.value

            result shouldBeEqualTo Trail.EMPTY
        }

    @Test
    fun `GIVEN the active trail no longer observed WHEN reading it THEN its last value is gone`() = testScope.runTest {
        activeSystemId.value = SystemId(SYSTEM_1)
        flyTo(SYSTEM_1, step = 0)
        val observer = repository.activeTrail.launchIn(backgroundScope)
        runCurrent()

        observer.cancel()
        runCurrent()
        val result = repository.activeTrail.value

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN the active trail observed WHEN reading the vehicles THEN are observed too, keeping the link open`() =
        testScope.runTest {
            repository.activeTrail.launchIn(backgroundScope)

            runCurrent()
            val result = vehicles.subscriptionCount.value

            result shouldBeEqualTo 1
        }

    /** A fresh 3D fix, then a position at [step]. */
    private fun flyTo(systemId: Int, step: Int, session: SessionId = FIRST_SESSION) {
        record(systemId, trailGps(TRAIL_FIX_3D), session)
        record(systemId, trailPosition(step), session)
    }

    private fun record(systemId: Int, message: MavlinkMessage, session: SessionId = FIRST_SESSION) {
        store.record(trailMessage(systemId, message, session), clock.markNow())
    }
}

private const val SYSTEM_1 = 1
private const val SYSTEM_2 = 2

/** Another replay: the same origin as [REPLAY_SESSION], told apart only by its epoch (#71). */
private val NEXT_REPLAY_SESSION = SessionId(DataOrigin.REPLAY, epoch = REPLAY_SESSION.epoch + 1)
