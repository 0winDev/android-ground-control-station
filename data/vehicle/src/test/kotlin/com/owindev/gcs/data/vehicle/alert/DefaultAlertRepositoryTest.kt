package com.owindev.gcs.data.vehicle.alert

import android.util.Log
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The telemetry is a hand-fed contract; the rule is the real one, on the test scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-028")
class DefaultAlertRepositoryTest {

    private val testScope = TestScope()
    private val clock = testScope.testScheduler.timeSource
    private val telemetryRepository: TelemetryRepository = mockk()
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val vehicleRepository: VehicleRepository = mockk()
    private val activeSystemId = MutableStateFlow<SystemId?>(VEHICLE)
    private val vehicles = MutableStateFlow(mapOf(VEHICLE to vehicle(VEHICLE, clock.markNow())))

    private lateinit var repository: DefaultAlertRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        repository = DefaultAlertRepository(
            telemetryRepository,
            vehicleRepository,
            AlertRule(),
            testScope.backgroundScope,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN no active vehicle WHEN observing the alerts THEN they are not evaluated, never all clear`() =
        testScope.runTest {
            activeSystemId.value = null
            repository.activeAlerts.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.NotEvaluated
        }

    @Test
    fun `GIVEN a vehicle with nothing wrong WHEN observing the alerts THEN they are evaluated and all clear`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 100)
            repository.activeAlerts.launchIn(backgroundScope)

            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(NONE)
        }

    @Test
    fun `GIVEN the alerts observed WHEN the battery reaches the low threshold THEN is raised and logged`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 100)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            activeTelemetry.value = telemetry(remaining = 20)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(1)))
            verifyOnce {
                Log.i(TAG, "Alert raised: BatteryLow (caution)")
            }
        }

    @Test
    fun `GIVEN telemetry that stops arriving WHEN 1 ms is left to its threshold THEN is not stale yet`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 100)
            repository.activeAlerts.launchIn(backgroundScope)

            advanceTimeBy(GPS_STALE_AFTER - 1.milliseconds)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(NONE)
        }

    @Test
    fun `GIVEN telemetry that stops arriving WHEN its threshold passes THEN is stale with nothing new arriving`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 100)
            repository.activeAlerts.launchIn(backgroundScope)

            advanceTimeBy(GPS_STALE_AFTER)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(STALE_GPS.raised(1)))
            verifyOnce {
                Log.i(TAG, "Alert raised: TelemetryStale(kind=GPS) (advisory)")
            }
        }

    @Test
    fun `GIVEN a low battery WHEN acknowledging it THEN is acknowledged and logged`() = testScope.runTest {
        activeTelemetry.value = telemetry(remaining = 20)
        repository.activeAlerts.launchIn(backgroundScope)
        runCurrent()

        repository.acknowledge(occurrence = 1)
        runCurrent()
        val result = repository.activeAlerts.value

        result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(1, acknowledged = true)))
        verifyOnce {
            Log.i(TAG, "Alert acknowledged: BatteryLow (caution)")
        }
    }

    @Test
    fun `GIVEN an acknowledged low battery WHEN new telemetry keeps it low THEN stays acknowledged`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            repository.acknowledge(occurrence = 1)

            activeTelemetry.value = telemetry(remaining = 19)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(1, acknowledged = true)))
        }

    @Test
    fun `GIVEN a critical battery WHEN acknowledging it THEN stays unacknowledged and nothing is logged`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 10)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            repository.acknowledge(occurrence = 1)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(CRITICAL.raised(1)))
            verifyNever {
                Log.i(TAG, "Alert acknowledged: BatteryCritical (emergency)")
            }
        }

    @Test
    fun `GIVEN a low battery WHEN the active vehicle goes away THEN is cleared, logged and not evaluated`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            activeTelemetry.value = null
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.NotEvaluated
            verifyOnce {
                Log.i(TAG, "Alert cleared: BatteryLow (caution)")
            }
        }

    @Test
    fun `GIVEN an acknowledged alert WHEN observed again after nobody did THEN starts afresh, a new occurrence`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            val observer = repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            repository.acknowledge(occurrence = 1)
            runCurrent()
            observer.cancel()
            runCurrent()

            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(2)))
        }

    @Test
    fun `GIVEN a low battery raised again WHEN acknowledging the occurrence seen before THEN the new one is not`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            activeTelemetry.value = telemetry(remaining = 50)
            runCurrent()
            activeTelemetry.value = telemetry(remaining = 20)
            runCurrent()

            repository.acknowledge(occurrence = 1)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(2)))
        }

    @Test
    fun `GIVEN alerts no longer observed WHEN reading them THEN are not evaluated, never all clear`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            val observer = repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            observer.cancel()
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.NotEvaluated
        }

    @Test
    fun `GIVEN an acknowledged low battery WHEN another vehicle becomes active THEN its low battery is a new one`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            repository.acknowledge(occurrence = 1)
            runCurrent()

            activeSystemId.value = OTHER_VEHICLE
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo Alerts.Evaluated(listOf(LOW.raised(2)))
        }

    @Test
    fun `GIVEN the alerts observed WHEN reading the vehicles THEN are observed too, deciding the active one`() =
        testScope.runTest {
            repository.activeAlerts.launchIn(backgroundScope)

            runCurrent()
            val result = vehicles.subscriptionCount.value

            result shouldBeEqualTo 1
        }

    @Test
    fun `GIVEN the alerts observed WHEN reading the telemetry THEN is observed too, keeping the link open`() =
        testScope.runTest {
            repository.activeAlerts.launchIn(backgroundScope)

            runCurrent()
            val result = activeTelemetry.subscriptionCount.value

            result shouldBeEqualTo 1
        }

    /** Battery and GPS with a 3D fix, received now. */
    private fun telemetry(remaining: Int): Telemetry {
        val now = clock.markNow()
        return Telemetry(battery = Timed(battery(remaining), now), gps = Timed(GPS_3D_FIX, now))
    }
}

private const val TAG = "AlertRepository"

private val VEHICLE = SystemId(1)
private val OTHER_VEHICLE = SystemId(2)

private val NONE = emptyList<ActiveAlert>()
private val LOW = Alert.BatteryLow
private val CRITICAL = Alert.BatteryCritical
private val STALE_GPS = Alert.TelemetryStale(TelemetryKind.GPS)

private val GPS_STALE_AFTER = StalenessRule.DEFAULT_THRESHOLDS.getValue(TelemetryKind.GPS)

private val GPS_3D_FIX = GpsStatus(
    fixType = GpsFixType.Fix3d,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)

private fun vehicle(systemId: SystemId, heardAt: TimeMark) = Vehicle(
    state = VehicleState(systemId, VehicleType.COPTER, FlightMode.Named("Stabilize"), armed = false),
    link = VehicleLink.CONNECTED,
    lastHeartbeat = heardAt,
)

private fun Alert.raised(occurrence: Long, acknowledged: Boolean = false) =
    ActiveAlert(alert = this, occurrence = occurrence, acknowledged = acknowledged)

private fun battery(remaining: Int) = Battery(
    voltage = 12.6,
    current = 0.0,
    remaining = remaining,
    monitored = true,
    healthy = true,
)
