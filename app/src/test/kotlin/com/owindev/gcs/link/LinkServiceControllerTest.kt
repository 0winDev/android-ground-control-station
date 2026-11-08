package com.owindev.gcs.link

import app.cash.turbine.test
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The background link's decisions (REQ-046), in virtual time on the test scheduler's clock. */
@Tag("REQ-046")
class LinkServiceControllerTest {

    private val testScope = TestScope()
    private val activeSystemId = MutableStateFlow<SystemId?>(SYSTEM_1)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val vehicleRepository: VehicleRepository = mockk()
    private val wakeHold: WakeHold = mockk()

    private lateinit var controller: LinkServiceController

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        controller = LinkServiceController(
            vehicleRepository,
            BackgroundLinkRule(LinkLossRule(), RecordingRule()),
            wakeHold,
        )
    }

    @Test
    fun `GIVEN a connected disarmed vehicle WHEN observing THEN the notice shows it and offers Disconnect`() =
        testScope.runTest {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))

            controller.notices.test {
                val result = awaitItem()

                result shouldBeEqualTo LinkNotice(state(armed = false), connected = true, disconnectOffered = true)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `GIVEN a connected armed vehicle WHEN observing THEN the notice hides Disconnect`() = testScope.runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

        controller.notices.test {
            val result = awaitItem()

            result shouldBeEqualTo LinkNotice(state(armed = true), connected = true, disconnectOffered = false)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GIVEN a disarmed vehicle that goes silent WHEN 10 min pass THEN the notices end`() = testScope.runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false, link = VehicleLink.LOST))

        controller.notices.test {
            skipItems(1)

            awaitComplete()
        }
    }

    @Test
    fun `GIVEN a vehicle last heard armed WHEN an hour passes silent THEN the notices go on`() = testScope.runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true, link = VehicleLink.LOST))
        controller.notices.test {
            skipItems(1)

            testScheduler.advanceTimeBy(AN_HOUR)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GIVEN a replay starts WHEN observing THEN the notices end`() = testScope.runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
        controller.notices.test {
            skipItems(1)

            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false, origin = DataOrigin.REPLAY))

            awaitComplete()
        }
    }

    @Test
    fun `GIVEN a disarmed vehicle WHEN Disconnect arrives THEN the service stops`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))

        val result = controller.actionFor(LinkRequest.DISCONNECT)

        result shouldBeEqualTo LinkAction.STOP
    }

    @Test
    fun `GIVEN an armed vehicle WHEN Disconnect arrives THEN it is refused`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

        val result = controller.actionFor(LinkRequest.DISCONNECT)

        result shouldBeEqualTo LinkAction.REFUSE
    }

    @Test
    fun `GIVEN a disarmed vehicle WHEN the app is removed from recents THEN the service stops`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))

        val result = controller.actionFor(LinkRequest.TASK_REMOVED)

        result shouldBeEqualTo LinkAction.STOP
    }

    @Test
    fun `GIVEN an armed vehicle WHEN the app is removed from recents THEN it is ignored`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

        val result = controller.actionFor(LinkRequest.TASK_REMOVED)

        result shouldBeEqualTo LinkAction.REFUSE
    }

    @Test
    fun `GIVEN an armed vehicle WHEN the notification is dismissed THEN it is posted again`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

        val result = controller.actionFor(LinkRequest.NOTIFICATION_DISMISSED)

        result shouldBeEqualTo LinkAction.REPOST
    }

    @Test
    fun `GIVEN the service started WHEN it stops THEN the wake lock is released`() {
        every { wakeHold.acquire() } just runs
        every { wakeHold.release() } just runs
        controller.started()

        controller.stopped()

        verifyOnce {
            wakeHold.acquire()
            wakeHold.release()
        }
    }

    @Test
    fun `GIVEN the service starting WHEN it starts THEN the wake lock is held and not released`() {
        every { wakeHold.acquire() } just runs

        controller.started()

        verifyOnce {
            wakeHold.acquire()
        }
        verifyNever {
            wakeHold.release()
        }
    }

    private fun state(armed: Boolean) = VehicleState(SYSTEM_1, VehicleType.COPTER, LOITER, armed = armed)

    private fun vehicle(
        armed: Boolean,
        link: VehicleLink = VehicleLink.CONNECTED,
        origin: DataOrigin = DataOrigin.LIVE,
    ) = Vehicle(
        state = state(armed),
        link = link,
        lastHeartbeat = testScope.testScheduler.timeSource.markNow() - HEARD_AGO,
        origin = origin,
    )
}

private val SYSTEM_1 = SystemId(1)
private val LOITER = FlightMode.Named("Loiter")
private val HEARD_AGO = 1.seconds
private val AN_HOUR = 60.minutes
