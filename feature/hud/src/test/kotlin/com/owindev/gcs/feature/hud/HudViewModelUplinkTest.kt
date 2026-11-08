package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRepository
import com.owindev.gcs.core.domain.link.UplinkRule
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The HUD's warning that the vehicle may not be hearing this GCS (REQ-045), on the test scheduler's clock. */
@Tag("REQ-045")
class HudViewModelUplinkTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS))
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val health = MutableStateFlow(UplinkHealth.HEALTHY)
    private val linkRepository: LinkRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val alertRepository: AlertRepository = mockk()
    private val uplinkRepository: UplinkRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns MutableStateFlow<Telemetry?>(null)
        every { alertRepository.activeAlerts } returns MutableStateFlow<Alerts>(Alerts.NotEvaluated)
        every { uplinkRepository.health } returns health
        viewModel = HudViewModel(
            linkRepository,
            vehicleRepository,
            telemetryRepository,
            alertRepository,
            uplinkRepository,
            UplinkRule(),
            LinkLossRule(),
            StalenessRule(),
            DeviceClock(mainDispatcher.testDispatcher.scheduler.timeSource),
        )
    }

    @Test
    fun `GIVEN three failed HEARTBEAT periods WHEN connected THEN the HUD says sends are failing`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.CONNECTED)
            health.value = UplinkHealth(failedHeartbeatPeriods = 3)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().uplink() shouldBeEqualTo UplinkDoubt.SendsFailing
        }
    }

    @Test
    fun `GIVEN two failed HEARTBEAT periods WHEN connected THEN there is no warning`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.CONNECTED)
            health.value = UplinkHealth(failedHeartbeatPeriods = 2)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().uplink().shouldBeNull()
        }
    }

    @Test
    fun `GIVEN a command without answer 3,4 s ago WHEN connected THEN the HUD says so with its age in seconds`() =
        runTest {
            turbineScope {
                heardVehicle(VehicleLink.CONNECTED)
                health.value = UplinkHealth(unansweredSince = testScheduler.timeSource.markNow() - UNANSWERED_AGO)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().uplink() shouldBeEqualTo UplinkDoubt.CommandUnanswered(3.seconds)
            }
        }

    @Test
    fun `GIVEN a command without answer WHEN time passes THEN its age counts up`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.CONNECTED)
            health.value = UplinkHealth(unansweredSince = testScheduler.timeSource.markNow())
            val states = viewModel.state.testIn(backgroundScope)

            delay(TWO_AND_A_HALF_SECONDS)

            states.expectMostRecentItem().uplink() shouldBeEqualTo UplinkDoubt.CommandUnanswered(2.seconds)
        }
    }

    @Test
    fun `GIVEN a warning shown WHEN the uplink is healthy again THEN the warning clears`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.CONNECTED)
            health.value = UplinkHealth(failedHeartbeatPeriods = 3)
            val states = viewModel.state.testIn(backgroundScope)

            health.value = UplinkHealth.HEALTHY

            states.expectMostRecentItem().uplink().shouldBeNull()
        }
    }

    @Test
    fun `GIVEN failing sends WHEN the vehicle is lost THEN link lost shows instead`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.LOST, heardAgo = TIMEOUT)
            health.value = UplinkHealth(failedHeartbeatPeriods = 3)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeInstanceOf HudUiState.LinkLost::class
        }
    }

    private fun TestScope.heardVehicle(link: VehicleLink, heardAgo: Duration = Duration.ZERO) {
        activeSystemId.value = SYSTEM_ID
        vehicles.value = mapOf(
            SYSTEM_ID to Vehicle(
                state = VehicleState(
                    systemId = SYSTEM_ID,
                    type = VehicleType.COPTER,
                    flightMode = GUIDED,
                    armed = true,
                ),
                link = link,
                lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
                origin = DataOrigin.LIVE,
            ),
        )
    }
}

private const val PORT = 14550
private const val DATAGRAMS = 42L
private val SYSTEM_ID = SystemId(1)
private val GUIDED = FlightMode.Named("Guided")
private val TIMEOUT = 3.seconds
private val UNANSWERED_AGO = 3_400.milliseconds
private val TWO_AND_A_HALF_SECONDS = 2_500.milliseconds

private fun HudUiState.uplink(): UplinkDoubt? = (this as? HudUiState.Connected)?.uplink
