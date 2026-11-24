package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.DataOrigin
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
import com.owindev.gcs.core.domain.vehicle.command.RouteAcceptance
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.coVerifyNever
import com.owindev.gcs.core.testing.coVerifyOnce
import io.mockk.coEvery
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

@Tag("REQ-051")
class HudRouteViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val commandKey = MutableStateFlow<CommandKey?>(KEY)
    private val route = MutableStateFlow<VehicleRoute?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val commandRepository: CommandRepository = mockk()
    private val routeRepository: CommandRouteRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()

    private lateinit var viewModel: HudRouteViewModel

    @BeforeEach
    fun setUp() {
        every { commandRepository.commandKey } returns commandKey
        every { routeRepository.route } returns route
        every { vehicleRepository.vehicles } returns vehicles
        viewModel = HudRouteViewModel(
            commands = commandRepository,
            routes = routeRepository,
            vehicles = vehicleRepository,
            linkLoss = LinkLossRule(),
        )
    }

    @Test
    fun `GIVEN a vehicle heard only from a new route WHEN observing THEN both addresses are shown`() = runTest {
        turbineScope {
            heardLive()
            route.value = changedRoute()

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo
                RouteUiState(notice = RouteNotice.Changed(from = OLD_ROUTE, to = NEW_ROUTE))
        }
    }

    @Test
    fun `GIVEN a vehicle heard from two routes WHEN its review is requested THEN none opens`() = runTest {
        turbineScope {
            heardLive()
            route.value = alternatingRoute()
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onReviewRequested()
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo
                RouteUiState(
                    notice = RouteNotice.Alternating(pinned = OLD_ROUTE, addresses = listOf(NEW_ROUTE, OLD_ROUTE)),
                )
        }
    }

    @Test
    fun `GIVEN a lost vehicle heard last from a new route WHEN its review is requested THEN nothing is shown`() =
        runTest {
            turbineScope {
                heardLive(link = VehicleLink.LOST)
                route.value = changedRoute()
                val states = viewModel.state.testIn(backgroundScope)

                viewModel.onReviewRequested()
                val result = states.expectMostRecentItem()

                result shouldBeEqualTo RouteUiState()
            }
        }

    @Test
    fun `GIVEN a pinned route WHEN observing THEN nothing is shown`() = runTest {
        turbineScope {
            heardLive()
            route.value = VehicleRoute(KEY, pinned = OLD_ROUTE, heard = mapOf(OLD_ROUTE to markNow()))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo RouteUiState()
        }
    }

    @Test
    fun `GIVEN a new route heard alone WHEN its review is requested THEN the sheet shows both addresses`() = runTest {
        turbineScope {
            heardLive()
            route.value = changedRoute()
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onReviewRequested()
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo RouteSheet(SYSTEM.value, OFFER)
        }
    }

    @Test
    fun `GIVEN the review of a new route WHEN it is printed THEN no address shows`() = runTest {
        turbineScope {
            heardLive()
            route.value = changedRoute()
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onReviewRequested()

            val result = states.expectMostRecentItem().toString()

            listOf(OLD_ROUTE.host in result, NEW_ROUTE.host in result) shouldBeEqualTo listOf(false, false)
        }
    }

    @Test
    fun `GIVEN the review of a new route WHEN cancelled THEN it closes and nothing is accepted`() = runTest {
        turbineScope {
            heardLive()
            route.value = changedRoute()
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onReviewRequested()

            viewModel.onCancelled()
            val result = states.expectMostRecentItem().sheet

            coVerifyNever {
                routeRepository.acceptRoute(any())
            }
            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN the review of a new route WHEN the link is lost by age alone and comes back THEN it never reopens`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onReviewRequested()

                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(PAST_TIMEOUT)
                heardLive()
                val result = states.expectMostRecentItem().sheet

                result shouldBeEqualTo null
            }
        }

    @Tag("REQ-050")
    @Test
    fun `GIVEN the review of a new route WHEN the slide confirms it THEN it is accepted and the sheet closes`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                coEvery { routeRepository.acceptRoute(OFFER) } returns RouteAcceptance.Accepted
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onReviewRequested()

                viewModel.onConfirmed(OFFER)
                val result = states.expectMostRecentItem().sheet

                coVerifyOnce {
                    routeRepository.acceptRoute(OFFER)
                }
                result shouldBeEqualTo null
            }
        }

    @Tag("REQ-050")
    @Test
    fun `GIVEN an acceptance found outdated WHEN the route still changed THEN it asks for another slide`() = runTest {
        turbineScope {
            heardLive()
            route.value = changedRoute()
            val moved = changedRoute(NEXT_KEY)
            coEvery { routeRepository.acceptRoute(OFFER) } coAnswers {
                commandKey.value = NEXT_KEY
                route.value = moved
                RouteAcceptance.Outdated
            }
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onReviewRequested()

            viewModel.onConfirmed(OFFER)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo RouteSheet(SYSTEM.value, OFFER.copy(key = NEXT_KEY), slideAgain = true)
        }
    }

    @Tag("REQ-050")
    @Test
    fun `GIVEN an acceptance found outdated WHEN the vehicle is heard from another route THEN nothing reopens`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                val elsewhere = changedRoute(NEXT_KEY, to = OTHER_ROUTE)
                coEvery { routeRepository.acceptRoute(OFFER) } coAnswers {
                    commandKey.value = NEXT_KEY
                    route.value = elsewhere
                    RouteAcceptance.Outdated
                }
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onReviewRequested()

                viewModel.onConfirmed(OFFER)
                val result = states.expectMostRecentItem().sheet

                result shouldBeEqualTo null
            }
        }

    @Tag("REQ-050")
    @Test
    fun `GIVEN a review unobserved WHEN its slide comes on a key that moved THEN it asks for another slide`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                viewModel.onReviewRequested()
                commandKey.value = NEXT_KEY
                route.value = changedRoute(NEXT_KEY)

                viewModel.onConfirmed(OFFER)
                val result = viewModel.state.testIn(backgroundScope).expectMostRecentItem().sheet

                coVerifyNever {
                    routeRepository.acceptRoute(any())
                }
                result shouldBeEqualTo RouteSheet(SYSTEM.value, OFFER.copy(key = NEXT_KEY), slideAgain = true)
            }
        }

    @Tag("REQ-050")
    @Test
    fun `GIVEN the review of a new route WHEN the key moves THEN the sheet closes and its slide accepts nothing`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                val moved = changedRoute(NEXT_KEY)
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onReviewRequested()

                commandKey.value = NEXT_KEY
                route.value = moved
                viewModel.onConfirmed(OFFER)
                val result = states.expectMostRecentItem().sheet

                coVerifyNever {
                    routeRepository.acceptRoute(any())
                }
                result shouldBeEqualTo null
            }
        }

    @Test
    fun `GIVEN the review of a new route WHEN the route alternates and changes again THEN it never reopens`() =
        runTest {
            turbineScope {
                heardLive()
                route.value = changedRoute()
                val alternating = alternatingRoute()
                val changedAgain = changedRoute()
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onReviewRequested()

                route.value = alternating
                route.value = changedAgain
                val result = states.expectMostRecentItem().sheet

                result shouldBeEqualTo null
            }
        }

    private fun TestScope.markNow() = testScheduler.timeSource.markNow()

    private fun TestScope.heardLive(link: VehicleLink = VehicleLink.CONNECTED) {
        vehicles.value = mapOf(
            SYSTEM to Vehicle(
                state = VehicleState(SYSTEM, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = true),
                link = link,
                lastHeartbeat = markNow(),
                origin = DataOrigin.LIVE,
            ),
        )
    }

    private fun TestScope.changedRoute(key: CommandKey = KEY, to: RouteAddress = NEW_ROUTE): VehicleRoute {
        val now = markNow()
        return VehicleRoute(key, pinned = OLD_ROUTE, heard = mapOf(to to now, OLD_ROUTE to now - LONG_SILENT))
    }

    private fun TestScope.alternatingRoute(): VehicleRoute {
        val now = markNow()
        return VehicleRoute(KEY, pinned = OLD_ROUTE, heard = mapOf(NEW_ROUTE to now, OLD_ROUTE to now - SHORT_SILENT))
    }

    private companion object {
        val SYSTEM = SystemId(1)
        val KEY = CommandKey(SYSTEM, connection = 4)
        val NEXT_KEY = KEY.copy(connection = 5)
        val OLD_ROUTE = RouteAddress("192.168.1.20", port = 14550)
        val NEW_ROUTE = RouteAddress("10.0.0.7", port = 14551)
        val OTHER_ROUTE = RouteAddress("10.0.0.9", port = 14552)
        val OFFER = RouteOffer(KEY, from = OLD_ROUTE, to = NEW_ROUTE)
        val LONG_SILENT: Duration = 4.seconds
        val SHORT_SILENT: Duration = 1.seconds
        val PAST_TIMEOUT: Duration = 3.5.seconds
    }
}
