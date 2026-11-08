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
    fun `GIVEN the service running WHEN the vehicle is heard THEN it is not started again`() = runTest {
        state.setRunning(true)
        viewModel.starts.test {
            heard(vehicle(armed = true))

            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
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
    fun `GIVEN the service running with its notification hidden WHEN notifications are allowed THEN it is posted`() =
        runTest {
            state.setRunning(true)
            hidden(true)
            viewModel.postAgain.test {
                hidden(false)

                val result = awaitItem()

                result shouldBeEqualTo Unit
            }
        }

    @Test
    fun `GIVEN no service and the notification hidden WHEN notifications are allowed THEN nothing is posted`() =
        runTest {
            hidden(true)
            viewModel.postAgain.test {
                hidden(false)

                val result = cancelAndConsumeRemainingEvents()

                result shouldBeEqualTo emptyList()
            }
        }

    @Test
    fun `GIVEN the service running with its notification hidden WHEN it stops THEN nothing is posted`() = runTest {
        state.setRunning(true)
        hidden(true)
        viewModel.postAgain.test {
            state.setRunning(false)

            val result = cancelAndConsumeRemainingEvents()

            result shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `GIVEN the service running with its notification shown WHEN the device is read again THEN nothing is posted`() =
        runTest {
            state.setRunning(true)
            hidden(false)
            viewModel.postAgain.test {
                state.refresh(notificationHidden = false, batteryOptimized = true, backgroundRestricted = false)

                val result = cancelAndConsumeRemainingEvents()

                result shouldBeEqualTo emptyList()
            }
        }

    private fun hidden(notificationHidden: Boolean) {
        state.refresh(notificationHidden, batteryOptimized = false, backgroundRestricted = false)
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
