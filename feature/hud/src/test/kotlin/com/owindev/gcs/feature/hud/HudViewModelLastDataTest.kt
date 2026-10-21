package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.StalenessRule
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

/** The last data kept on screen under the link-loss banner, on the test scheduler's clock as in [HudViewModelTest]. */
class HudViewModelLastDataTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val linkRepository: LinkRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val telemetryRepository: TelemetryRepository = mockk()
    private val alertRepository: AlertRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns MutableStateFlow(LinkTraffic.Receiving(port = PORT, datagrams = 1))
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
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

    @Tag("REQ-017")
    @Tag("REQ-029")
    @Test
    fun `GIVEN telemetry but the vehicle lost WHEN observing the state THEN keeps it as the last data`() = runTest {
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
                lastData = TelemetryPanel(attitude = fresh(LEVEL)),
            )
        }
    }

    @Tag("REQ-017")
    @Tag("REQ-037")
    @Test
    fun `GIVEN telemetry of another session and the vehicle lost WHEN observing the state THEN none is last data`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(SYSTEM_1, VehicleLink.LOST, heardAgo = FOUR_SECONDS))
                activeTelemetry.value =
                    Telemetry(attitude = Timed(LEVEL, testScheduler.timeSource.markNow()), origin = DataOrigin.REPLAY)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkLost(
                    lastKnown = GUIDED_ARMED_1,
                    sinceLastHeartbeat = FOUR_SECONDS,
                    otherVehicles = emptyList(),
                )
            }
        }
}

private const val PORT = 14550
private val SYSTEM_1 = SystemId(1)
private val GUIDED_ARMED_1 =
    VehicleStatus(systemId = SYSTEM_1.value, flightMode = FlightMode.Named("Guided"), armed = true)
private val FOUR_SECONDS = 4.seconds
private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

private fun <T> fresh(value: T) = Reading(value, stale = false, age = Duration.ZERO)

private fun TestScope.vehicle(systemId: SystemId, link: VehicleLink, heardAgo: Duration) = Vehicle(
    state = VehicleState(
        systemId = systemId,
        type = VehicleType.COPTER,
        flightMode = FlightMode.Named("Guided"),
        armed = true,
    ),
    link = link,
    lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
    origin = DataOrigin.LIVE,
)
