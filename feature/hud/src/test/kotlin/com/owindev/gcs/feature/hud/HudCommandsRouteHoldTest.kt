package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.preflight.PreflightCheck
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
import com.owindev.gcs.core.domain.vehicle.command.CommandRouteRepository
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import com.owindev.gcs.core.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** [HudCommandsViewModel] while the vehicle's route is not the one commands take. */
@Tag("REQ-051")
class HudCommandsRouteHoldTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val commandKey = MutableStateFlow<CommandKey?>(KEY)
    private val route = MutableStateFlow<VehicleRoute?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val commandRepository: CommandRepository = mockk()
    private val routeRepository: CommandRouteRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val preflightRepository: PreflightRepository = mockk()

    private lateinit var viewModel: HudCommandsViewModel

    @BeforeEach
    fun setUp() {
        every { commandRepository.commandKey } returns commandKey
        every { commandRepository.pending } returns MutableStateFlow<PendingCommand?>(null)
        every { routeRepository.route } returns route
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { preflightRepository.checkFor(any()) } returns PreflightCheck(items = emptyList(), connection = 4)
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
    fun `GIVEN a vehicle heard only from a new route WHEN observing THEN every command is held`() = runTest {
        turbineScope {
            armedOnTheGround()
            route.value = changedRoute()

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar shouldBeEqualTo heldBar(CommandUnavailable.RouteChanged)
        }
    }

    @Test
    fun `GIVEN a vehicle heard from two routes WHEN observing THEN every command is held`() = runTest {
        turbineScope {
            armedOnTheGround()
            route.value = alternatingRoute()

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar shouldBeEqualTo heldBar(CommandUnavailable.RouteAlternating)
        }
    }

    @Test
    fun `GIVEN a vehicle heard live with no route known WHEN observing THEN every command is held`() = runTest {
        turbineScope {
            armedOnTheGround()

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar shouldBeEqualTo heldBar(CommandUnavailable.RouteUnknown)
        }
    }

    @Test
    fun `GIVEN a key that moved before its route WHEN RTL is requested THEN it is held and nothing opens`() = runTest {
        turbineScope {
            armedOnTheGround()
            route.value = VehicleRoute(KEY, pinned = OLD_ROUTE, heard = mapOf(OLD_ROUTE to markNow()))
            commandKey.value = NEXT_KEY
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)
            val result = states.expectMostRecentItem()

            listOf(result.bar, result.sheet) shouldBeEqualTo listOf(heldBar(CommandUnavailable.RouteUnknown), null)
        }
    }

    @Test
    fun `GIVEN a new route heard alone WHEN RTL is requested THEN no confirmation opens`() = runTest {
        turbineScope {
            armedOnTheGround()
            route.value = changedRoute()
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN a new route held WHEN it is accepted on a new key THEN the commands are offered again`() = runTest {
        turbineScope {
            armedOnTheGround()
            route.value = changedRoute()
            val accepted = VehicleRoute(NEXT_KEY, pinned = NEW_ROUTE, heard = mapOf(NEW_ROUTE to markNow()))
            val states = viewModel.state.testIn(backgroundScope)

            commandKey.value = NEXT_KEY
            route.value = accepted
            val result = states.expectMostRecentItem().bar

            result shouldBeEqualTo CommandBar(
                ground = OfferedCommand(VehicleCommand.TAKE_OFF),
                returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH),
                land = OfferedCommand(VehicleCommand.LAND),
            )
        }
    }

    private fun TestScope.markNow() = testScheduler.timeSource.markNow()

    private fun TestScope.armedOnTheGround() {
        val now = markNow()
        vehicles.value = mapOf(
            SYSTEM to Vehicle(
                state = VehicleState(SYSTEM, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = true),
                link = VehicleLink.CONNECTED,
                lastHeartbeat = now,
                origin = DataOrigin.LIVE,
            ),
        )
        activeTelemetry.value = Telemetry(
            origin = DataOrigin.LIVE,
            position = Timed(ON_THE_GROUND, now),
            landed = Timed(LandedState.ON_GROUND, now),
        )
    }

    private fun TestScope.changedRoute(): VehicleRoute {
        val now = markNow()
        return VehicleRoute(KEY, pinned = OLD_ROUTE, heard = mapOf(NEW_ROUTE to now, OLD_ROUTE to now - LONG_SILENT))
    }

    private fun TestScope.alternatingRoute(): VehicleRoute {
        val now = markNow()
        return VehicleRoute(KEY, pinned = OLD_ROUTE, heard = mapOf(NEW_ROUTE to now, OLD_ROUTE to now - SHORT_SILENT))
    }

    private fun heldBar(reason: CommandUnavailable) = CommandBar(
        ground = OfferedCommand(VehicleCommand.TAKE_OFF, reason),
        returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH, reason),
        land = OfferedCommand(VehicleCommand.LAND, reason),
    )

    private companion object {
        val SYSTEM = SystemId(1)
        val KEY = CommandKey(SYSTEM, connection = 4)
        val NEXT_KEY = KEY.copy(connection = 5)
        val OLD_ROUTE = RouteAddress("192.168.1.20", port = 14550)
        val NEW_ROUTE = RouteAddress("10.0.0.7", port = 14551)
        val LONG_SILENT: Duration = 4.seconds
        val SHORT_SILENT: Duration = 1.seconds
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
