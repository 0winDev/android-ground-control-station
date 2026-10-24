package com.owindev.gcs.navigation

import app.cash.turbine.test
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.attention.FlightAttentionRule
import com.owindev.gcs.core.domain.link.DataOrigin
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
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The guard on the recordings screen (ADR 0007): what takes the operator back to the flight screen and what does not.
 * The navigation applies each return with `backToFlight` (`DestinationTest`).
 */
@Tag("REQ-017")
@Tag("REQ-037")
class FlightGuardViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val clock = TestTimeSource()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val alerts = MutableStateFlow<Alerts>(Alerts.NotEvaluated)
    private val vehicleRepository: VehicleRepository = mockk()
    private val alertRepository: AlertRepository = mockk()

    private lateinit var viewModel: FlightGuardViewModel

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns vehicles
        every { alertRepository.activeAlerts } returns alerts
        viewModel = FlightGuardViewModel(vehicleRepository, alertRepository, FlightAttentionRule(LinkLossRule()))
    }

    @Test
    fun `GIVEN the recordings open WHEN a live vehicle arms THEN the app returns to flight and stays there`() =
        runTest {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
            viewModel.returnToFlight.test {
                vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

                val result = awaitItem() to viewModel.mayLeaveFlight.value

                result shouldBeEqualTo (Unit to false)
            }
        }

    @Test
    fun `GIVEN the recordings open WHEN the live link is lost THEN the app returns to flight`() = runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
        viewModel.returnToFlight.test {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false, link = VehicleLink.LOST))

            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    @Test
    fun `GIVEN the recordings open WHEN a live emergency is raised THEN the app returns to flight`() = runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
        viewModel.returnToFlight.test {
            alerts.value = Alerts.Evaluated(listOf(alert(Alert.BatteryUnhealthy, DataOrigin.LIVE)))

            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    @Test
    fun `GIVEN the recordings open WHEN a caution is raised THEN the operator stays`() = runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
        viewModel.returnToFlight.test {
            alerts.value = Alerts.Evaluated(listOf(alert(Alert.GpsFixLost, DataOrigin.LIVE)))

            val result = viewModel.mayLeaveFlight.value

            result shouldBeEqualTo true
            expectNoEvents()
        }
    }

    @Test
    fun `GIVEN a replay WHEN its recorded emergency and link loss come THEN the operator stays`() = runTest {
        viewModel.returnToFlight.test {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true, VehicleLink.LOST, DataOrigin.REPLAY))
            alerts.value = Alerts.Evaluated(listOf(alert(Alert.BatteryCritical, DataOrigin.REPLAY)))

            val result = viewModel.mayLeaveFlight.value

            result shouldBeEqualTo true
            expectNoEvents()
        }
    }

    @Test
    fun `GIVEN a disarmed vehicle lost long ago WHEN the operator opens the recordings THEN it is allowed`() = runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false, link = VehicleLink.LOST))
        viewModel.returnToFlight.test {
            val result = viewModel.mayLeaveFlight.value

            result shouldBeEqualTo true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GIVEN the guard not watched WHEN the app is in the background THEN nothing observes the live vehicles`() {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))

        val result = vehicles.subscriptionCount.value to alerts.subscriptionCount.value

        result shouldBeEqualTo (0 to 0)
    }

    @Test
    fun `GIVEN the guard watched WHEN the watch stops THEN nothing observes the live vehicles any longer`() = runTest {
        vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))

        viewModel.returnToFlight.test { cancelAndIgnoreRemainingEvents() }

        vehicles.subscriptionCount.value shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN a vehicle that armed while the app was in the background WHEN watched again THEN returns to flight`() =
        runTest {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = false))
            viewModel.returnToFlight.test { cancelAndIgnoreRemainingEvents() }
            vehicles.value = mapOf(SYSTEM_1 to vehicle(armed = true))

            viewModel.returnToFlight.test {
                val result = awaitItem() to viewModel.mayLeaveFlight.value

                result shouldBeEqualTo (Unit to false)
            }
        }

    private fun vehicle(
        armed: Boolean,
        link: VehicleLink = VehicleLink.CONNECTED,
        origin: DataOrigin = DataOrigin.LIVE,
    ) = Vehicle(
        state = VehicleState(SYSTEM_1, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
        link = link,
        lastHeartbeat = clock.markNow(),
        origin = origin,
    )

    private fun alert(alert: Alert, origin: DataOrigin) = ActiveAlert(
        alert = alert,
        occurrence = 1,
        raisedAt = clock.markNow(),
        origin = origin,
    )

    private companion object {
        val SYSTEM_1 = SystemId(1)
    }
}
