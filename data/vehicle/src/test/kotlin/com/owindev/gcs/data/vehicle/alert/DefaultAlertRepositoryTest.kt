package com.owindev.gcs.data.vehicle.alert

import android.util.Log
import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.time.Duration.Companion.milliseconds
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

    private lateinit var repository: DefaultAlertRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        repository = DefaultAlertRepository(telemetryRepository, AlertRule(), testScope.backgroundScope)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN no active vehicle WHEN observing the alerts THEN there are none`() = testScope.runTest {
        repository.activeAlerts.launchIn(backgroundScope)

        runCurrent()
        val result = repository.activeAlerts.value

        result shouldBeEqualTo NONE
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

            result shouldBeEqualTo listOf(LOW)
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

            result shouldBeEqualTo NONE
        }

    @Test
    fun `GIVEN telemetry that stops arriving WHEN its threshold passes THEN is stale with nothing new arriving`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 100)
            repository.activeAlerts.launchIn(backgroundScope)

            advanceTimeBy(GPS_STALE_AFTER)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo listOf(STALE)
        }

    @Test
    fun `GIVEN a low battery WHEN acknowledging it THEN is acknowledged and logged`() = testScope.runTest {
        activeTelemetry.value = telemetry(remaining = 20)
        repository.activeAlerts.launchIn(backgroundScope)
        runCurrent()

        repository.acknowledge(Alert.BatteryLow)
        runCurrent()
        val result = repository.activeAlerts.value

        result shouldBeEqualTo listOf(LOW.copy(acknowledged = true))
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
            repository.acknowledge(Alert.BatteryLow)

            activeTelemetry.value = telemetry(remaining = 19)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo listOf(LOW.copy(acknowledged = true))
        }

    @Test
    fun `GIVEN a critical battery WHEN acknowledging it THEN stays unacknowledged and nothing is logged`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 10)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            repository.acknowledge(Alert.BatteryCritical)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo listOf(CRITICAL)
            verifyNever {
                Log.i(TAG, "Alert acknowledged: BatteryCritical (emergency)")
            }
        }

    @Test
    fun `GIVEN a low battery WHEN the active vehicle goes away THEN the alert is cleared and logged`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()

            activeTelemetry.value = null
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo NONE
            verifyOnce {
                Log.i(TAG, "Alert cleared: BatteryLow (caution)")
            }
        }

    @Test
    fun `GIVEN an acknowledged alert WHEN the alerts are observed again after nobody did THEN starts afresh`() =
        testScope.runTest {
            activeTelemetry.value = telemetry(remaining = 20)
            val observer = repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            repository.acknowledge(Alert.BatteryLow)
            runCurrent()
            observer.cancel()
            runCurrent()

            repository.activeAlerts.launchIn(backgroundScope)
            runCurrent()
            val result = repository.activeAlerts.value

            result shouldBeEqualTo listOf(LOW)
        }

    @Test
    fun `GIVEN alerts no longer observed WHEN reading them THEN their last value is gone`() = testScope.runTest {
        activeTelemetry.value = telemetry(remaining = 20)
        val observer = repository.activeAlerts.launchIn(backgroundScope)
        runCurrent()

        observer.cancel()
        runCurrent()
        val result = repository.activeAlerts.value

        result shouldBeEqualTo NONE
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

private val NONE = emptyList<ActiveAlert>()
private val LOW = ActiveAlert(Alert.BatteryLow)
private val CRITICAL = ActiveAlert(Alert.BatteryCritical)
private val STALE = ActiveAlert(Alert.TelemetryStale)

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

private fun battery(remaining: Int) = Battery(
    voltage = 12.6,
    current = 0.0,
    remaining = remaining,
    monitored = true,
    healthy = true,
)
