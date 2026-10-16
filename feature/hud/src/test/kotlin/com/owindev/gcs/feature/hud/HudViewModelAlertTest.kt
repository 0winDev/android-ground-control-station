package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
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
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The HUD's alert banner (REQ-028), on the test scheduler's clock as in [HudViewModelTest]. */
@Tag("REQ-028")
class HudViewModelAlertTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS))
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val activeAlerts = MutableStateFlow<Alerts>(Alerts.NotEvaluated)
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
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { alertRepository.activeAlerts } returns activeAlerts
        viewModel = HudViewModel(
            linkRepository,
            vehicleRepository,
            telemetryRepository,
            alertRepository,
            LinkLossRule(),
            StalenessRule(),
        )
    }

    @Test
    fun `GIVEN alerts not evaluated yet WHEN connected THEN no banner, never all clear`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.CONNECTED, heardAgo = Duration.ZERO)
            activeAlerts.value = Alerts.NotEvaluated

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().alertBanner() shouldBeEqualTo AlertBanner.Hidden
        }
    }

    @Test
    fun `GIVEN a low battery with a fresh reading WHEN connected THEN the banner says its remaining energy`() =
        runTest {
            turbineScope {
                heardVehicle(VehicleLink.CONNECTED, heardAgo = Duration.ZERO)
                activeTelemetry.value = Telemetry(battery = Timed(LOW_BATTERY, testScheduler.timeSource.markNow()))
                activeAlerts.value = Alerts.Evaluated(listOf(raised(Alert.BatteryLow, occurrence = 1)))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().alertBanner() shouldBeEqualTo AlertBanner.Showing(
                    alert = Alert.BatteryLow,
                    occurrence = 1,
                    acknowledgeable = true,
                    others = 0,
                    batteryRemaining = LOW_REMAINING,
                )
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN an emergency WHEN the link is lost THEN link lost shows and no banner`() = runTest {
        turbineScope {
            heardVehicle(VehicleLink.LOST, heardAgo = TIMEOUT)
            activeAlerts.value = Alerts.Evaluated(listOf(raised(Alert.BatteryCritical, occurrence = 1)))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                lastKnown = GUIDED_ARMED,
                sinceLastHeartbeat = TIMEOUT,
                otherVehicles = emptyList(),
            )
        }
    }

    @Test
    fun `GIVEN a shown caution WHEN the operator acknowledges it THEN its occurrence is acknowledged`() {
        every { alertRepository.acknowledge(any()) } just runs

        viewModel.onAlertAcknowledged(occurrence = OCCURRENCE)

        verifyOnce {
            alertRepository.acknowledge(OCCURRENCE)
        }
    }

    /** The active copter, whose last HEARTBEAT arrived [heardAgo] on the test scheduler's clock. */
    private fun TestScope.heardVehicle(link: VehicleLink, heardAgo: Duration) {
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
            ),
        )
    }

    private fun TestScope.raised(alert: Alert, occurrence: Long) = ActiveAlert(
        alert = alert,
        occurrence = occurrence,
        raisedAt = testScheduler.timeSource.markNow(),
    )
}

private const val PORT = 14550
private const val DATAGRAMS = 42L
private const val OCCURRENCE = 4L
private const val LOW_REMAINING = 18
private val SYSTEM_ID = SystemId(1)
private val GUIDED = FlightMode.Named("Guided")
private val GUIDED_ARMED = VehicleStatus(systemId = SYSTEM_ID.value, flightMode = GUIDED, armed = true)
private val TIMEOUT = LinkLossRule.DEFAULT_TIMEOUT
private val LOW_BATTERY = Battery(
    voltage = 11.1,
    current = 0.0,
    remaining = LOW_REMAINING,
    monitored = true,
    healthy = true,
)

private fun HudUiState.alertBanner(): AlertBanner? = (this as? HudUiState.Connected)?.alert
