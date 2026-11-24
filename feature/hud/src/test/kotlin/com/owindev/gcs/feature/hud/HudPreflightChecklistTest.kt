package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightCheck
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.LandedState
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.time.DeviceClock
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.CommandRouteRepository
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.coVerifyOnce
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** [HudCommandsViewModel]'s arm sheet: the pre-flight checklist and its acknowledgements. */
@Tag("REQ-048")
class HudPreflightChecklistTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val commandKey = MutableStateFlow<CommandKey?>(KEY)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val route = MutableStateFlow<VehicleRoute?>(pinnedOn(KEY))
    private var check = PreflightCheck(listOf(LINK_OK), CONNECTION)
    private val commandRepository: CommandRepository = mockk()
    private val routeRepository: CommandRouteRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val preflightRepository: PreflightRepository = mockk()
    private val sentAcknowledgements: CapturingSlot<PreflightAcknowledgements> = slot()

    private lateinit var viewModel: HudCommandsViewModel

    @BeforeEach
    fun setUp() {
        every { commandRepository.commandKey } returns commandKey
        every { commandRepository.pending } returns MutableStateFlow<PendingCommand?>(null)
        every { routeRepository.route } returns route
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { preflightRepository.checkFor(any()) } answers { check }
        viewModel = HudCommandsViewModel(
            commands = commandRepository,
            routes = routeRepository,
            vehicles = vehicleRepository,
            telemetry = telemetryRepository,
            preflight = preflightRepository,
            altitudeRule = TakeOffAltitudeRule(),
            deviceClock = DeviceClock(mainDispatcher.testDispatcher.scheduler.timeSource),
            linkLoss = LinkLossRule(),
            landed = LandedRule(),
            groundPosition = GroundPositionRule(),
        )
    }

    @Test
    fun `GIVEN every item green WHEN arm is requested THEN the checklist offers the slide`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LINK_OK, GPS_OK), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.ARM)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo armSheet(
                key = ARM_KEY,
                unavailable = null,
                checklist = listOf(ChecklistItem(LINK_OK), ChecklistItem(GPS_OK)),
            )
        }
    }

    @Test
    fun `GIVEN a red item WHEN arm is requested THEN the checklist opens with it first and no slide`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LINK_OK, LIMITS_AMBER, GPS_RED), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.ARM)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo armSheet(
                key = null,
                unavailable = CommandUnavailable.PreflightRed(items = 1),
                checklist = listOf(ChecklistItem(GPS_RED), ChecklistItem(LIMITS_AMBER), ChecklistItem(LINK_OK)),
            )
        }
    }

    @Test
    fun `GIVEN an amber item WHEN it is acknowledged THEN the checklist offers the slide`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LINK_OK, LIMITS_AMBER), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.ARM)

            viewModel.onCautionAcknowledged(LIMITS_AMBER)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo armSheet(
                key = ARM_KEY,
                unavailable = null,
                checklist = listOf(ChecklistItem(LIMITS_AMBER, acknowledged = true), ChecklistItem(LINK_OK)),
            )
        }
    }

    @Test
    fun `GIVEN an amber item unacknowledged WHEN the checklist shows THEN it asks for the acknowledgement`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.ARM)
            val result = states.expectMostRecentItem().sheet?.let { it.key to it.unavailable }

            result shouldBeEqualTo (null to CommandUnavailable.CautionsToAcknowledge)
        }
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN its reason changes and comes back THEN it is not acknowledged`() =
        runTest {
            turbineScope {
                disarmedOnTheGround()
                check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onCommandRequested(VehicleCommand.ARM)
                viewModel.onCautionAcknowledged(LIMITS_AMBER)

                check = PreflightCheck(listOf(LIMITS_OTHER_AMBER), CONNECTION)
                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
                check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
                val result = states.expectMostRecentItem().sheet?.checklist

                result shouldBeEqualTo listOf(ChecklistItem(LIMITS_AMBER))
            }
        }

    @Test
    fun `GIVEN an amber item that changed WHEN a tap drawn earlier acknowledges it THEN nothing is acknowledged`() =
        runTest {
            turbineScope {
                disarmedOnTheGround()
                check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onCommandRequested(VehicleCommand.ARM)
                check = PreflightCheck(listOf(LIMITS_OTHER_AMBER), CONNECTION)

                viewModel.onCautionAcknowledged(LIMITS_AMBER)
                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
                val result = states.expectMostRecentItem().sheet?.checklist

                result shouldBeEqualTo listOf(ChecklistItem(LIMITS_OTHER_AMBER))
            }
        }

    @Test
    fun `GIVEN no checklist open WHEN an amber item is acknowledged THEN arm still asks for it`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCautionAcknowledged(LIMITS_AMBER)
            val result = states.expectMostRecentItem().bar?.ground

            result shouldBeEqualTo OfferedCommand(VehicleCommand.ARM, CommandUnavailable.CautionsToAcknowledge)
        }
    }

    @Test
    fun `GIVEN an amber item acknowledged WHEN 2 min pass THEN the slide is gone`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.ARM)
            viewModel.onCautionAcknowledged(LIMITS_AMBER)

            mainDispatcher.testDispatcher.scheduler.advanceTimeBy(PreflightAcknowledgements.MAX_AGE)
            disarmedOnTheGround()
            mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
            val result = states.expectMostRecentItem().sheet?.key

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN an amber item acknowledged WHEN the slide confirms THEN arm carries the acknowledgement`() = runTest {
        disarmedOnTheGround()
        check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
        coEvery { commandRepository.arm(KEY, capture(sentAcknowledgements)) } returns CommandResult.Accepted
        viewModel.onCommandRequested(VehicleCommand.ARM)
        viewModel.onCautionAcknowledged(LIMITS_AMBER)

        viewModel.onCommandConfirmed(ARM_KEY)
        val result = sentAcknowledgements.captured.covers(LIMITS_AMBER, check)

        coVerifyOnce {
            commandRepository.arm(KEY, any())
        }
        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN the vehicle's pre-arm reason 5 s old WHEN the checklist shows THEN it says its age`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            val failing = PreflightItem(
                PreflightKind.VEHICLE_PREARM,
                PreflightColour.RED,
                PreflightReason.PreArmFailing(PRE_ARM_TEXT, current = true, receivedAt = markNow() - 5.seconds),
            )
            check = PreflightCheck(listOf(failing), CONNECTION)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.ARM)
            val result = states.expectMostRecentItem().sheet?.checklist

            result shouldBeEqualTo listOf(ChecklistItem(failing, reasonAge = 6.seconds))
        }
    }

    @Test
    fun `GIVEN the checklist open WHEN the vehicle arms THEN it closes and never comes back by itself`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.ARM)

            vehicles.value = mapOf(SYSTEM to vehicle(armed = true))
            mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
            vehicles.value = mapOf(SYSTEM to vehicle(armed = false))
            mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN the checklist open WHEN the link is lost THEN it closes`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.ARM)

            vehicles.value = mapOf(SYSTEM to vehicle(armed = false, link = VehicleLink.LOST))
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN nobody observes for over 5 s THEN it must be acknowledged again`() =
        runTest {
            turbineScope {
                disarmedOnTheGround()
                check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
                val observed = viewModel.state.testIn(backgroundScope)
                viewModel.onCommandRequested(VehicleCommand.ARM)
                viewModel.onCautionAcknowledged(LIMITS_AMBER)

                observed.cancelAndIgnoreRemainingEvents()
                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(ACKNOWLEDGED_ONCE)
                disarmedOnTheGround()
                val result = viewModel.state.testIn(backgroundScope).expectMostRecentItem().bar?.ground

                result shouldBeEqualTo OfferedCommand(VehicleCommand.ARM, CommandUnavailable.CautionsToAcknowledge)
            }
        }

    @Test
    fun `GIVEN an amber item acknowledged WHEN an arm is accepted THEN the next arm asks for it again`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
            coEvery { commandRepository.arm(KEY, any()) } returns CommandResult.Accepted
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.ARM)
            viewModel.onCautionAcknowledged(LIMITS_AMBER)

            viewModel.onCommandConfirmed(ARM_KEY)
            mainDispatcher.testDispatcher.scheduler.advanceTimeBy(RECHECKED)
            val result = states.expectMostRecentItem().bar?.ground

            result shouldBeEqualTo OfferedCommand(VehicleCommand.ARM, CommandUnavailable.CautionsToAcknowledge)
        }
    }

    @Test
    fun `GIVEN an unobserved checklist on an older key WHEN an amber item is acknowledged THEN nothing is`() = runTest {
        turbineScope {
            disarmedOnTheGround()
            check = PreflightCheck(listOf(LIMITS_AMBER), CONNECTION)
            viewModel.onCommandRequested(VehicleCommand.ARM)
            check = PreflightCheck(listOf(LIMITS_AMBER), NEXT_KEY.connection)
            route.value = pinnedOn(NEXT_KEY)
            commandKey.value = NEXT_KEY

            viewModel.onCautionAcknowledged(LIMITS_AMBER)
            val result = viewModel.state.testIn(backgroundScope).expectMostRecentItem().bar?.ground

            result shouldBeEqualTo OfferedCommand(VehicleCommand.ARM, CommandUnavailable.CautionsToAcknowledge)
        }
    }

    private fun TestScope.markNow() = testScheduler.timeSource.markNow()

    private fun TestScope.disarmedOnTheGround() {
        val now = markNow()
        vehicles.value = mapOf(SYSTEM to vehicle(armed = false))
        activeTelemetry.value = Telemetry(
            origin = DataOrigin.LIVE,
            position = Timed(ON_THE_GROUND, now),
            landed = Timed(LandedState.ON_GROUND, now),
        )
    }

    private fun TestScope.vehicle(armed: Boolean, link: VehicleLink = VehicleLink.CONNECTED) = Vehicle(
        state = VehicleState(SYSTEM, VehicleType.COPTER, FlightMode.Named("Loiter"), armed),
        link = link,
        lastHeartbeat = markNow(),
        origin = DataOrigin.LIVE,
    )

    private fun armSheet(key: ConfirmKey?, unavailable: CommandUnavailable?, checklist: List<ChecklistItem>) =
        CommandSheet(
            command = VehicleCommand.ARM,
            systemId = SYSTEM.value,
            altitudeMetres = null,
            key = key,
            unavailable = unavailable,
            checklist = checklist,
        )

    private companion object {
        const val CONNECTION = 4L
        const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"
        val SYSTEM = SystemId(1)
        val KEY = CommandKey(SYSTEM, connection = CONNECTION)
        val ARM_KEY = ConfirmKey(VehicleCommand.ARM, KEY, altitudeMetres = null)
        val RECHECKED = 0.5.seconds
        val NEXT_KEY = KEY.copy(connection = CONNECTION + 1)
        val PINNED_ADDRESS = RouteAddress("192.168.1.20", port = 14550)
        val ACKNOWLEDGED_ONCE: Duration = 6.seconds

        /** One address heard alone is pinned however old its mark. */
        fun pinnedOn(key: CommandKey) =
            VehicleRoute(key, pinned = PINNED_ADDRESS, heard = mapOf(PINNED_ADDRESS to TimeSource.Monotonic.markNow()))
        val LINK_OK = PreflightItem(PreflightKind.LINK, PreflightColour.GREEN, PreflightReason.Ok)
        val GPS_OK = PreflightItem(PreflightKind.GPS, PreflightColour.GREEN, PreflightReason.Ok)
        val GPS_RED = PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NoFix3d)
        val LIMITS_AMBER =
            PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead)
        val LIMITS_OTHER_AMBER = PreflightItem(
            PreflightKind.BATTERY_LIMITS,
            PreflightColour.AMBER,
            PreflightReason.FailsafeNotArmed(
                low = FailsafeStatus.NO_THRESHOLD,
                critical = FailsafeStatus.NO_THRESHOLD,
            ),
        )
        val ON_THE_GROUND = GlobalPosition(
            latitude = 40.0,
            longitude = -3.0,
            altitudeMsl = 600.0,
            altitudeAboveHome = 0.0,
            velocityNorth = 0.0,
            velocityEast = 0.0,
            velocityDown = 0.0,
            heading = null,
        )
    }
}
