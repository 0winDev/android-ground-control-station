package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Vehicle ages come from time marks on the test scheduler's clock, so the ViewModel's one-second ticks
 * (virtual `delay`) and the ages it reads move together.
 */
class HudViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS))
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val linkRepository: LinkRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        viewModel = HudViewModel(linkRepository, vehicleRepository, LinkLossRule())
    }

    @Tag("REQ-005")
    @Test
    fun `GIVEN datagrams but no vehicle WHEN observing the state THEN waits for a vehicle with the count`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.WaitingForVehicle(
                port = PORT,
                datagramCount = DATAGRAMS,
            )
        }
    }

    @Tag("REQ-005")
    @Test
    fun `GIVEN the port is in use WHEN observing the state THEN shows PortUnavailable`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Unavailable(port = PORT)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.PortUnavailable(port = PORT)
        }
    }

    @Tag("REQ-005")
    @Test
    fun `GIVEN the link failed WHEN observing the state THEN shows LinkError`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Failed

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkError
        }
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN the active vehicle is heard WHEN observing the state THEN shows its mode and armed state`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_1,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-018")
    @Test
    fun `GIVEN a mode without a documented name WHEN observing the state THEN shows the raw mode`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(
                SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO, mode = UNKNOWN_MODE),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Connected(
                vehicle = VehicleStatus(systemId = SYSTEM_1.value, flightMode = UNKNOWN_MODE, armed = true),
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN a lower system ID than the active vehicle WHEN observing the state THEN shows it apart`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_2
            vehicles.value = mapOf(
                SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO),
                SYSTEM_2 to vehicle(SYSTEM_2, VehicleLink.CONNECTED, heardAgo = Duration.ZERO),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_2,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = listOf(SYSTEM_1.value),
            )
        }
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN another vehicle that is no longer heard WHEN observing the state THEN it is not shown`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(
                SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO),
                SYSTEM_2 to vehicle(SYSTEM_2, VehicleLink.LOST, heardAgo = TIMEOUT),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_1,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN the active vehicle is LOST and another is heard WHEN observing the state THEN shows LinkLost`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(
                    SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT),
                    SYSTEM_2 to vehicle(SYSTEM_2, VehicleLink.CONNECTED, heardAgo = Duration.ZERO),
                )

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TIMEOUT,
                    otherVehicles = listOf(SYSTEM_2.value),
                )
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the active vehicle is LOST WHEN observing the state THEN shows LinkLost with its last state`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TIMEOUT,
                    otherVehicles = emptyList(),
                )
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the link is lost WHEN time passes THEN the time since the last HEARTBEAT keeps counting`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))
            val states = viewModel.state.testIn(backgroundScope)

            delay(FOUR_AND_A_HALF_SECONDS)
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = SEVEN_SECONDS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a vehicle still CONNECTED past the timeout WHEN observing the state THEN shows LinkLost`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = FOUR_SECONDS))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = FOUR_SECONDS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a CONNECTED vehicle that goes silent WHEN the timeout passes THEN shows LinkLost on time`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = ONE_SECOND))
            val states = viewModel.state.testIn(backgroundScope)

            delay(TWO_AND_A_HALF_SECONDS)
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = TIMEOUT,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the link failed on this device after the vehicle was lost WHEN observing THEN LinkLost shows it`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.Failed
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TIMEOUT,
                    otherVehicles = emptyList(),
                    localFault = LocalLinkFault.Failed,
                )
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the port is in use while the vehicle was lost WHEN observing THEN LinkLost shows the port`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Unavailable(port = PORT)
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = TIMEOUT,
                otherVehicles = emptyList(),
                localFault = LocalLinkFault.PortUnavailable(port = PORT),
            )
        }
    }

    @Tag("REQ-005")
    @Test
    fun `GIVEN the link failed while the vehicle is still heard WHEN observing the state THEN shows LinkError`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.Failed
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkError
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a timeout that is not whole seconds WHEN the vehicle goes silent THEN shows LinkLost at the timeout`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = ONE_SECOND))
                val custom = HudViewModel(linkRepository, vehicleRepository, LinkLossRule(timeout = CUSTOM_TIMEOUT))
                val states = custom.state.testIn(backgroundScope)

                delay(JUST_PAST_CUSTOM_TIMEOUT)
                val result = states.expectMostRecentItem()

                result shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TWO_SECONDS,
                    otherVehicles = emptyList(),
                )
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the HUD was not observed while the link timed out WHEN observed again THEN never shows it connected`() =
        runTest {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))
            viewModel.state.first { it is HudUiState.Connected }
            delay(PAST_STOP_AND_LINK_TIMEOUTS)

            val result = viewModel.state.first { it != HudUiState.Starting }

            result shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = PAST_STOP_AND_LINK_TIMEOUTS,
                otherVehicles = emptyList(),
            )
        }

    @Tag("REQ-020")
    @Test
    fun `GIVEN the active vehicle is not in the map yet WHEN observing the state THEN waits for it`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.WaitingForVehicle(
                port = PORT,
                datagramCount = DATAGRAMS,
            )
        }
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link is lost WHEN the vehicle is heard again THEN shows it connected`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))
            val states = viewModel.state.testIn(backgroundScope)

            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_1,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN a recovering link and a heard vehicle WHEN observing THEN shows it connected and unstable`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Recovering(port = PORT, datagrams = DATAGRAMS)
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_1,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
                linkUnstable = true,
            )
        }
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link is recovering before any vehicle WHEN observing the state THEN waits and shows it unstable`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.Recovering(port = PORT, datagrams = DATAGRAMS)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.WaitingForVehicle(
                    port = PORT,
                    datagramCount = DATAGRAMS,
                    linkUnstable = true,
                )
            }
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link is recovering after the vehicle was lost WHEN observing THEN LinkLost shows it unstable`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.Recovering(port = PORT, datagrams = DATAGRAMS)
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TIMEOUT,
                    otherVehicles = emptyList(),
                    localFault = LocalLinkFault.Unstable,
                )
            }
        }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the link is recovering WHEN it recovers THEN the unstable warning goes away`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Recovering(port = PORT, datagrams = DATAGRAMS)
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))
            val states = viewModel.state.testIn(backgroundScope)

            traffic.value = LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS)
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo HudUiState.Connected(
                vehicle = GUIDED_ARMED_1,
                port = PORT,
                datagramCount = DATAGRAMS,
                otherVehicles = emptyList(),
            )
        }
    }
}

private const val PORT = 14550
private const val DATAGRAMS = 42L
private const val UNKNOWN_MODE_NUMBER = 42L
private val SYSTEM_1 = SystemId(1)
private val SYSTEM_2 = SystemId(2)
private val GUIDED = FlightMode.Named("Guided")
private val UNKNOWN_MODE = FlightMode.Unknown(UNKNOWN_MODE_NUMBER)
private val GUIDED_ARMED_1 = VehicleStatus(systemId = SYSTEM_1.value, flightMode = GUIDED, armed = true)
private val GUIDED_ARMED_2 = VehicleStatus(systemId = SYSTEM_2.value, flightMode = GUIDED, armed = true)
private val TIMEOUT = LinkLossRule.DEFAULT_TIMEOUT
private val ONE_SECOND = 1.seconds
private val FOUR_SECONDS = 4.seconds
private val SEVEN_SECONDS = 7.seconds
private val TWO_SECONDS = 2.seconds
private val CUSTOM_TIMEOUT = 2_500.milliseconds

/** Just past the 2.5 s custom timeout and well before the next whole second (3 s). */
private val JUST_PAST_CUSTOM_TIMEOUT = 1_600.milliseconds

/** Longer than the HUD's 5 s stop timeout plus the 3 s link-loss timeout. */
private val PAST_STOP_AND_LINK_TIMEOUTS = 9.seconds
private val TWO_AND_A_HALF_SECONDS = 2_500.milliseconds
private val FOUR_AND_A_HALF_SECONDS = 4_500.milliseconds

/** A copter whose last HEARTBEAT arrived [heardAgo] on the test scheduler's clock. */
private fun TestScope.vehicle(systemId: SystemId, link: VehicleLink, heardAgo: Duration, mode: FlightMode = GUIDED) =
    Vehicle(
        state = VehicleState(systemId = systemId, type = VehicleType.COPTER, flightMode = mode, armed = true),
        link = link,
        lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
    )
