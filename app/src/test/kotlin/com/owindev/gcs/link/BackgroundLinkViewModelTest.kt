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
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** When the activity starts the background link (REQ-046). */
@Tag("REQ-046")
class BackgroundLinkViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val clock = TestTimeSource()
    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val vehicleRepository: VehicleRepository = mockk()

    private lateinit var viewModel: BackgroundLinkViewModel

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        viewModel = BackgroundLinkViewModel(vehicleRepository, BackgroundLinkRule(LinkLossRule(), RecordingRule()))
    }

    @Test
    fun `GIVEN no vehicle WHEN the first live HEARTBEAT arrives THEN the service is started once`() = runTest {
        viewModel.starts.test {
            heard(vehicle(DataOrigin.LIVE))
            heard(vehicle(DataOrigin.LIVE))

            val result = awaitItem()

            result shouldBeEqualTo Unit
            expectNoEvents()
        }
    }

    @Test
    fun `GIVEN no vehicle WHEN a replayed vehicle is heard THEN the service is not started`() = runTest {
        viewModel.starts.test {
            heard(vehicle(DataOrigin.REPLAY))

            expectNoEvents()
        }
    }

    private fun heard(vehicle: Vehicle) {
        activeSystemId.value = SYSTEM_1
        vehicles.value = mapOf(SYSTEM_1 to vehicle)
    }

    private fun vehicle(origin: DataOrigin) = Vehicle(
        state = VehicleState(SYSTEM_1, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = false),
        link = VehicleLink.CONNECTED,
        lastHeartbeat = clock.markNow(),
        origin = origin,
    )
}

private val SYSTEM_1 = SystemId(1)
