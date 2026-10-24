package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.time.DeviceClock
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The HUD while a recording plays or right after (REQ-037), on the test scheduler's clock as in [HudViewModelTest]. */
@Tag("REQ-037")
class HudViewModelReplayTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS))
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val linkRepository: LinkRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val alertRepository: AlertRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns MutableStateFlow<Telemetry?>(null)
        every { alertRepository.activeAlerts } returns MutableStateFlow<Alerts>(Alerts.NotEvaluated)
        viewModel = HudViewModel(
            linkRepository,
            vehicleRepository,
            telemetryRepository,
            alertRepository,
            LinkLossRule(),
            StalenessRule(),
            DeviceClock(mainDispatcher.testDispatcher.scheduler.timeSource),
        )
    }

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

    @Test
    fun `GIVEN a replayed vehicle still listed WHEN the live link is back THEN it is not shown as heard`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(
                SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.CONNECTED, heardAgo = Duration.ZERO)
                    .copy(origin = DataOrigin.REPLAY),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.WaitingForVehicle(
                port = PORT,
                datagramCount = DATAGRAMS,
            )
        }
    }

    @Test
    fun `GIVEN a replayed vehicle lost WHEN observing the state THEN its link loss is marked recorded`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Replaying(datagrams = DATAGRAMS)
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(
                SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = LOST_FOR).copy(origin = DataOrigin.REPLAY),
            )

            val result = viewModel.state.testIn(backgroundScope)

            (result.expectMostRecentItem() as HudUiState.LinkLost).recorded shouldBeEqualTo true
        }
    }

    @Test
    fun `GIVEN a live vehicle lost WHEN observing the state THEN its link loss is never marked recorded`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = LOST_FOR))

            val result = viewModel.state.testIn(backgroundScope)

            (result.expectMostRecentItem() as HudUiState.LinkLost).recorded shouldBeEqualTo false
        }
    }

    @Test
    fun `GIVEN a recording that cannot be read WHEN observing the state THEN says so, not a link error`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.ReplayFailed

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.ReplayUnreadable
        }
    }
}

private fun TestScope.vehicle(systemId: SystemId, link: VehicleLink, heardAgo: Duration) = Vehicle(
    state = VehicleState(systemId = systemId, type = VehicleType.COPTER, flightMode = GUIDED, armed = true),
    link = link,
    lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
    origin = DataOrigin.LIVE,
)

private const val PORT = 14550
private val LOST_FOR = 5.seconds
private const val DATAGRAMS = 42L
private val SYSTEM_1 = SystemId(1)
private val GUIDED = FlightMode.Named("Guided")
