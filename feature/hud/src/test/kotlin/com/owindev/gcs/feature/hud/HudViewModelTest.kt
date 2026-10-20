package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.geo.Mgrs
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
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
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val telemetryRepository: TelemetryRepository = mockk()
    private val activeAlerts = MutableStateFlow<Alerts>(Alerts.NotEvaluated)
    private val alertRepository: AlertRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { alertRepository.activeAlerts } returns activeAlerts
        viewModel = HudViewModel(
            linkRepository,
            vehicleRepository,
            telemetryRepository,
            alertRepository,
            LinkLossRule(),
            StalenessRule(),
            mainDispatcher.testDispatcher.scheduler.timeSource,
        )
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

    @Tag("REQ-037")
    @Test
    fun `GIVEN a recording playing but no vehicle WHEN observing the state THEN waits for one without a port`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.Replaying(datagrams = DATAGRAMS)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.WaitingForVehicle(
                    port = null,
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

    @Tag("REQ-019")
    @Test
    fun `GIVEN the port was taken while reopening WHEN observing the state THEN shows it is retrying`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.UnavailableRetrying(port = PORT)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.PortUnavailableRetrying(port = PORT)
        }
    }

    @Tag("REQ-019")
    @Test
    fun `GIVEN the port was taken while reopening after the vehicle was lost WHEN observing THEN LinkLost says so`() =
        runTest {
            turbineScope {
                traffic.value = LinkTraffic.UnavailableRetrying(port = PORT)
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = TIMEOUT))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = TIMEOUT,
                    otherVehicles = emptyList(),
                    localFault = LocalLinkFault.PortUnavailableRetrying(port = PORT),
                )
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
                val custom = HudViewModel(
                    linkRepository,
                    vehicleRepository,
                    telemetryRepository,
                    alertRepository,
                    LinkLossRule(timeout = CUSTOM_TIMEOUT),
                    StalenessRule(),
                    mainDispatcher.testDispatcher.scheduler.timeSource,
                )
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

    @Tag("REQ-029")
    @Tag("REQ-027")
    @Test
    fun `GIVEN fresh telemetry of the active vehicle WHEN observing the state THEN shows every part with its MGRS`() =
        runTest {
            turbineScope {
                connectedVehicle()
                val now = testScheduler.timeSource.markNow()
                activeTelemetry.value = Telemetry(
                    position = Timed(SITL_HOME, now),
                    attitude = Timed(LEVEL, now),
                    airData = Timed(HOVER, now),
                    battery = Timed(FULL_BATTERY, now),
                    gps = Timed(RTK_FIX, now),
                    origin = DataOrigin.LIVE,
                )

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo connected(
                    TelemetryPanel(
                        position = fresh(PositionReading(SITL_HOME, SITL_HOME_MGRS, verified = true)),
                        attitude = fresh(LEVEL),
                        airData = fresh(HOVER),
                        battery = fresh(FULL_BATTERY),
                        gps = fresh(RTK_FIX),
                    ),
                )
            }
        }

    @Tag("REQ-029")
    @Test
    fun `GIVEN only the attitude received WHEN observing the state THEN the other parts are not shown`() = runTest {
        turbineScope {
            connectedVehicle()
            activeTelemetry.value =
                Telemetry(attitude = Timed(LEVEL, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo connected(TelemetryPanel(attitude = fresh(LEVEL)))
        }
    }

    @Tag("REQ-029")
    @Tag("REQ-031")
    @Test
    fun `GIVEN angles the vehicle sent out of range WHEN observing the state THEN stay no value`() = runTest {
        turbineScope {
            connectedVehicle()
            val withoutRoll = Attitude(roll = null, pitch = 3.0, yaw = null)
            activeTelemetry.value =
                Telemetry(attitude = Timed(withoutRoll, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo connected(TelemetryPanel(attitude = fresh(withoutRoll)))
        }
    }

    @Tag("REQ-029")
    @Tag("REQ-027")
    @Test
    fun `GIVEN a position north of UTM WHEN observing the state THEN its MGRS is not available`() = runTest {
        turbineScope {
            connectedVehicle()
            val north = SITL_HOME.copy(latitude = NORTH_OF_UTM)
            activeTelemetry.value =
                Telemetry(position = Timed(north, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo connected(
                TelemetryPanel(position = fresh(PositionReading(north, Mgrs.NotAvailable, verified = false))),
            )
        }
    }

    @Tag("REQ-029")
    @Test
    fun `GIVEN a fresh position but the GPS without a fix WHEN observing the state THEN it is not verified`() =
        runTest {
            turbineScope {
                connectedVehicle()
                val now = testScheduler.timeSource.markNow()
                activeTelemetry.value =
                    Telemetry(position = Timed(SITL_HOME, now), gps = Timed(NO_FIX, now), origin = DataOrigin.LIVE)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo connected(
                    TelemetryPanel(
                        position = fresh(PositionReading(SITL_HOME, SITL_HOME_MGRS, verified = false)),
                        gps = fresh(NO_FIX),
                    ),
                )
            }
        }

    @Tag("REQ-029")
    @Tag("REQ-026")
    @Test
    fun `GIVEN attitude that stops arriving WHEN 1 ms is left to its threshold THEN is not stale yet`() = runTest {
        turbineScope {
            connectedVehicle()
            activeTelemetry.value =
                Telemetry(attitude = Timed(LEVEL, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)
            val states = viewModel.state.testIn(backgroundScope)

            delay(ATTITUDE_STALE_AFTER - 1.milliseconds)
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo connected(TelemetryPanel(attitude = fresh(LEVEL)))
        }
    }

    @Tag("REQ-029")
    @Tag("REQ-026")
    @Test
    fun `GIVEN attitude that stops arriving WHEN its threshold passes THEN is stale with nothing new arriving`() =
        runTest {
            turbineScope {
                connectedVehicle()
                activeTelemetry.value =
                    Telemetry(attitude = Timed(LEVEL, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)
                val states = viewModel.state.testIn(backgroundScope)

                delay(ATTITUDE_STALE_AFTER)
                val result = states.expectMostRecentItem()

                result shouldBeEqualTo connected(
                    TelemetryPanel(attitude = Reading(LEVEL, stale = true, age = ONE_SECOND)),
                )
            }
        }

    @Tag("REQ-029")
    @Tag("REQ-026")
    @Test
    fun `GIVEN a stale GPS WHEN time passes THEN its age counts in whole seconds, never under-stated`() = runTest {
        turbineScope {
            connectedVehicle()
            val received = testScheduler.timeSource.markNow() - TWO_AND_A_HALF_SECONDS
            activeTelemetry.value = Telemetry(gps = Timed(RTK_FIX, received), origin = DataOrigin.LIVE)
            val states = viewModel.state.testIn(backgroundScope)

            delay(SIX_HUNDRED_MILLISECONDS)
            val result = states.expectMostRecentItem()

            result shouldBeEqualTo connected(TelemetryPanel(gps = Reading(RTK_FIX, stale = true, age = FOUR_SECONDS)))
        }
    }

    @Tag("REQ-029")
    @Test
    fun `GIVEN telemetry but the vehicle lost WHEN observing the state THEN only the link loss is shown`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = FOUR_SECONDS))
            activeTelemetry.value =
                Telemetry(attitude = Timed(LEVEL, testScheduler.timeSource.markNow()), origin = DataOrigin.LIVE)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED_1,
                sinceLastHeartbeat = FOUR_SECONDS,
                otherVehicles = emptyList(),
            )
        }
    }

    @Tag("REQ-029")
    @Tag("REQ-026")
    @Test
    fun `GIVEN the link was lost WHEN the vehicle is heard again THEN its old telemetry shows stale with its age`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = FOUR_SECONDS))
                activeTelemetry.value = Telemetry(
                    attitude = Timed(LEVEL, testScheduler.timeSource.markNow() - FOUR_SECONDS),
                    origin = DataOrigin.LIVE,
                )
                val states = viewModel.state.testIn(backgroundScope)

                connectedVehicle()
                val result = states.expectMostRecentItem()

                result shouldBeEqualTo connected(
                    TelemetryPanel(attitude = Reading(LEVEL, stale = true, age = FIVE_SECONDS)),
                )
            }
        }

    /** The active vehicle, system 1, heard just now. */
    private fun TestScope.connectedVehicle() {
        activeSystemId.value = SYSTEM_1
        vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO))
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
private val FIVE_SECONDS = 5.seconds
private val TWO_SECONDS = 2.seconds
private val CUSTOM_TIMEOUT = 2_500.milliseconds

/** Just past the 2.5 s custom timeout and well before the next whole second (3 s). */
private val JUST_PAST_CUSTOM_TIMEOUT = 1_600.milliseconds

/** Longer than the HUD's 5 s stop timeout plus the 3 s link-loss timeout. */
private val PAST_STOP_AND_LINK_TIMEOUTS = 9.seconds
private val TWO_AND_A_HALF_SECONDS = 2_500.milliseconds
private val FOUR_AND_A_HALF_SECONDS = 4_500.milliseconds

private val ATTITUDE_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.ATTITUDE)
private val SIX_HUNDRED_MILLISECONDS = 600.milliseconds

/** Past 84°N, where MGRS uses UPS: no UTM square. */
private const val NORTH_OF_UTM = 85.0

/** ArduPilot SITL's home (CMAC, Canberra), as decoded in the telemetry tests. */
private val SITL_HOME = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = -0.013,
    velocityNorth = 0.01,
    velocityEast = -0.01,
    velocityDown = 0.0,
    heading = 359.35,
)

/** `55HFA9672084509`, from GeoConvert 2.7 in `core/domain/src/test/resources/geo/mgrs-reference.csv`. */
private val SITL_HOME_MGRS = Mgrs.Grid(zone = 55, band = 'H', square = "FA", easting = 96720, northing = 84509)
private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)
private val HOVER = AirData(airspeed = 0.0, groundspeed = 0.0, climbRate = 0.0, throttle = 40)
private val FULL_BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)

/** GPS_RAW_INT as ArduPilot SITL sent it before its first fix (#42): no fix, 3 satellites. */
private val NO_FIX = GpsStatus(
    fixType = GpsFixType.NoFix,
    satellitesVisible = 3,
    hdop = null,
    vdop = null,
    groundSpeed = null,
    courseOverGround = null,
    horizontalAccuracy = null,
    verticalAccuracy = null,
    yaw = null,
)
private val RTK_FIX = GpsStatus(
    fixType = GpsFixType.RtkFixed,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)

private fun <T> fresh(value: T) = Reading(value, stale = false, age = Duration.ZERO)

private fun connected(telemetry: TelemetryPanel) = HudUiState.Connected(
    vehicle = GUIDED_ARMED_1,
    port = PORT,
    datagramCount = DATAGRAMS,
    otherVehicles = emptyList(),
    telemetry = telemetry,
)

/** A copter whose last HEARTBEAT arrived [heardAgo] on the test scheduler's clock. */
private fun TestScope.vehicle(systemId: SystemId, link: VehicleLink, heardAgo: Duration, mode: FlightMode = GUIDED) =
    Vehicle(
        state = VehicleState(systemId = systemId, type = VehicleType.COPTER, flightMode = mode, armed = true),
        link = link,
        lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
        origin = DataOrigin.LIVE,
    )
