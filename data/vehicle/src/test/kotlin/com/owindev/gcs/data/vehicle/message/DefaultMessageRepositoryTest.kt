package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.REPLAY_SESSION
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.link.sessionOn
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.every
import io.mockk.mockk
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The vehicles are a hand-fed contract; the store is the real one. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-047")
class DefaultMessageRepositoryTest {

    private val testScope = TestScope()
    private val clock = TestTimeSource()
    private val vehicleRepository: VehicleRepository = mockk()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val activeVehicle = ActiveVehicle()
    private val store = MessageStore(activeVehicle, PreArmRule())
    private val session = MutableStateFlow(FIRST_SESSION)

    private lateinit var repository: DefaultMessageRepository

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns vehicles
        every { vehicleRepository.activeSystemId } returns activeSystemId
        repository = DefaultMessageRepository(store, vehicleRepository, sessionOn(session), testScope.backgroundScope)
    }

    @Test
    fun `GIVEN the active vehicle's texts WHEN observing them THEN they are shown newest first`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        record(wholeText(PRE_ARM_TEXT))
        record(wholeText(TEXT))
        repository.activeMessages.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeMessages.value

        result shouldBeEqualTo listOf(shown(TEXT), shown(PRE_ARM_TEXT))
    }

    @Test
    fun `GIVEN a pre-arm failure WHEN observing the reason THEN it is the latest pre-arm text`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        record(wholeText(PRE_ARM_TEXT))
        repository.preArmReason.launchIn(backgroundScope)

        runCurrent()
        val result = repository.preArmReason.value

        result shouldBeEqualTo shown(PRE_ARM_TEXT)
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN texts but no active vehicle WHEN observing them THEN there are none`() = testScope.runTest {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        record(wholeText(PRE_ARM_TEXT))
        repository.activeMessages.launchIn(backgroundScope)
        repository.preArmReason.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeMessages.value to repository.preArmReason.value

        result shouldBeEqualTo (emptyList<VehicleMessage>() to null)
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN one vehicle's texts WHEN another is the active one THEN there are none`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        record(wholeText(TEXT))
        repository.activeMessages.launchIn(backgroundScope)
        runCurrent()

        activeSystemId.value = SystemId(MESSAGE_SYSTEM_2)
        runCurrent()
        val result = repository.activeMessages.value

        result.shouldBeEmpty()
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN live texts WHEN a replay starts before the store moves on THEN none of them is shown`() =
        testScope.runTest {
            activate(MESSAGE_SYSTEM_1)
            record(wholeText(PRE_ARM_TEXT))
            repository.activeMessages.launchIn(backgroundScope)
            repository.preArmReason.launchIn(backgroundScope)
            runCurrent()

            session.value = REPLAY_SESSION
            runCurrent()
            val result = repository.activeMessages.value to repository.preArmReason.value

            result shouldBeEqualTo (emptyList<VehicleMessage>() to null)
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay WHEN its vehicle sends a text THEN it is shown as recorded`() = testScope.runTest {
        store.startSession(REPLAY_SESSION)
        session.value = REPLAY_SESSION
        activate(MESSAGE_SYSTEM_1, REPLAY_SESSION)
        record(wholeText(TEXT), REPLAY_SESSION)
        repository.activeMessages.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeMessages.value

        result shouldBeEqualTo listOf(shown(TEXT).copy(origin = DataOrigin.REPLAY))
    }

    @Test
    fun `GIVEN the texts no longer observed WHEN reading them THEN their last value is gone`() = testScope.runTest {
        activate(MESSAGE_SYSTEM_1)
        record(wholeText(TEXT))
        val observer = repository.activeMessages.launchIn(backgroundScope)
        runCurrent()

        observer.cancel()
        runCurrent()
        val result = repository.activeMessages.value

        result.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the pre-arm reason no longer observed WHEN reading it THEN its last value is gone`() =
        testScope.runTest {
            activate(MESSAGE_SYSTEM_1)
            record(wholeText(PRE_ARM_TEXT))
            val observer = repository.preArmReason.launchIn(backgroundScope)
            runCurrent()

            observer.cancel()
            runCurrent()
            val result = repository.preArmReason.value

            result.shouldBeNull()
        }

    @Test
    fun `GIVEN the texts observed WHEN reading the vehicles THEN are observed too, keeping the link open`() =
        testScope.runTest {
            repository.activeMessages.launchIn(backgroundScope)

            runCurrent()
            val result = vehicles.subscriptionCount.value

            result shouldBeEqualTo 1
        }

    /** [systemId] active in [session], as the store and the vehicles see it. */
    private fun activate(systemId: Int, session: SessionId = FIRST_SESSION) {
        if (session != FIRST_SESSION) activeVehicle.startReplay(session)
        activeVehicle.pinFirst(SystemId(systemId), session)
        activeSystemId.value = SystemId(systemId)
    }

    private fun record(message: MavlinkMessage, session: SessionId = FIRST_SESSION) {
        store.record(received(message, session), clock.markNow())
    }

    private fun shown(text: String) =
        VehicleMessage(MessageSeverity.CRITICAL, text, receivedAt = clock.markNow(), origin = DataOrigin.LIVE)

    private companion object {
        const val TEXT = "EKF3 IMU0 is using GPS"
    }
}
