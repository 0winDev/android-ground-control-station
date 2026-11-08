package com.owindev.gcs.link

import app.cash.turbine.test
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** When the activity starts the background link (REQ-046): by level, whenever it must run and is not running. */
@Tag("REQ-046")
class BackgroundLinkViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val clock = TestTimeSource()
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val vehicleRepository: VehicleRepository = mockk()
    private val state = BackgroundLinkState()

    private lateinit var viewModel: BackgroundLinkViewModel

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        viewModel = BackgroundLinkViewModel(
            vehicleRepository,
            BackgroundLinkRule(LinkLossRule(), RecordingRule()),
            state,
        )
    }

    @Test
    fun `GIVEN no vehicle WHEN the first live HEARTBEATs arrive THEN the service is started once`() = runTest {
        viewModel.starts.test {
            heard(vehicle(armed = false))
            heard(vehicle(armed = false))

            val result = awaitItem()

            result shouldBeEqualTo Unit
            expectNoEvents()
        }
    }

    @Test
    fun `GIVEN no vehicle WHEN a replayed vehicle is heard THEN the service is not started`() = runTest {
        viewModel.starts.test {
            heard(vehicle(armed = true, origin = DataOrigin.REPLAY))

            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running WHEN the activity starts THEN it is started again to keep the foreground`() =
        runTest {
            heard(vehicle(armed = true))
            state.setRunning(true)

            viewModel.starts.test {
                val result = awaitItem()

                result shouldBeEqualTo Unit
            }
        }

    @Test
    fun `GIVEN the service running and started WHEN more HEARTBEATs arrive THEN it is not started again`() = runTest {
        heard(vehicle(armed = true))
        state.setRunning(true)
        viewModel.starts.test {
            skipItems(1)

            heard(vehicle(armed = true))
            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running WHEN it stops while it must run THEN it is started again`() = runTest {
        heard(vehicle(armed = true))
        state.setRunning(true)
        viewModel.starts.test {
            skipItems(1)

            state.setRunning(false)
            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    @Test
    fun `GIVEN a Disconnect with the app visible WHEN the disarmed vehicle stays connected THEN it is not restarted`() =
        runTest {
            state.disconnectedByUser.value = true
            viewModel.starts.test {
                heard(vehicle(armed = false))

                val result = cancelAndConsumeRemainingEvents()

                result shouldBeEqualTo emptyList()
            }
        }

    @Test
    fun `GIVEN a Disconnect with the app visible WHEN the vehicle arms THEN the service is started`() = runTest {
        state.disconnectedByUser.value = true
        heard(vehicle(armed = false))
        viewModel.starts.test {
            heard(vehicle(armed = true))

            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    @Test
    fun `GIVEN a recreated activity with the vehicle already armed and no service WHEN observing THEN it starts`() =
        runTest {
            heard(vehicle(armed = true))

            viewModel.starts.test {
                val result = awaitItem()

                result shouldBeEqualTo Unit
            }
        }

    @Test
    fun `GIVEN a Disconnect WHEN the vehicle is lost and connected again THEN it is not restarted`() = runTest {
        state.disconnectedByUser.value = true
        viewModel.starts.test {
            heard(vehicle(armed = false, link = VehicleLink.LOST, heardAt = clock.markNow() - LONG_SILENT))
            heard(vehicle(armed = false))

            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running and every vehicle disarmed WHEN observed THEN notifications are asked`() = runTest {
        heard(vehicle(armed = false))
        state.setRunning(true)

        viewModel.asksNotifications.test {
            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    @Test
    fun `GIVEN every vehicle disarmed but no service WHEN observed THEN notifications are not asked`() = runTest {
        heard(vehicle(armed = false))

        viewModel.asksNotifications.test {
            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running over an armed vehicle WHEN observed THEN notifications are not asked`() = runTest {
        heard(vehicle(armed = true))
        state.setRunning(true)

        viewModel.asksNotifications.test {
            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running over an armed vehicle WHEN it disarms THEN notifications are asked`() = runTest {
        heard(vehicle(armed = true))
        state.setRunning(true)
        viewModel.asksNotifications.test {
            heard(vehicle(armed = false))

            val result = awaitItem()

            result shouldBeEqualTo Unit
        }
    }

    private fun heard(vehicle: Vehicle) {
        activeSystemId.value = SYSTEM_1
        vehicles.value = mapOf(SYSTEM_1 to vehicle)
    }

    private fun vehicle(
        armed: Boolean,
        link: VehicleLink = VehicleLink.CONNECTED,
        origin: DataOrigin = DataOrigin.LIVE,
        heardAt: TimeMark = clock.markNow(),
    ) = Vehicle(
        state = VehicleState(SYSTEM_1, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
        link = link,
        lastHeartbeat = heardAt,
        origin = origin,
    )
}

private val SYSTEM_1 = SystemId(1)
private val LONG_SILENT = 10.seconds
