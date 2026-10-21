package com.owindev.gcs.feature.map

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.Trail
import com.owindev.gcs.core.domain.telemetry.TrailPoint
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Ages come from time marks on the test scheduler's clock, so the ViewModel's rechecks and the ages move together. */
@Tag("REQ-034")
class MapViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val activeSystemId = MutableStateFlow<SystemId?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val vehicleRepository: VehicleRepository = mockk()
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private val activeTrail = MutableStateFlow<Trail?>(null)
    private val telemetryRepository: TelemetryRepository = mockk()

    private lateinit var viewModel: MapViewModel

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { telemetryRepository.activeTrail } returns activeTrail
        viewModel = MapViewModel(vehicleRepository, telemetryRepository, LinkLossRule(), StalenessRule())
    }

    @Tag("REQ-033")
    @Tag("REQ-035")
    @Test
    fun `GIVEN a verified position WHEN observing the map THEN draws the vehicle live, its home and trail`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = verified(CANBERRA, home = HOME)
            activeTrail.value = Trail(listOf(listOf(TRAIL_A, TRAIL_B), listOf(TRAIL_C)))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo MapUiState(
                vehicle = mapVehicle(PositionShown.Live),
                home = MapPoint(HOME.latitude, HOME.longitude),
                trail = listOf(listOf(TRAIL_A.mapPoint, TRAIL_B.mapPoint), listOf(TRAIL_C.mapPoint)),
            )
        }
    }

    @Tag("REQ-029")
    @Test
    fun `GIVEN a fresh position without a 3D fix WHEN observing the map THEN draws it not verified`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = withFix(CANBERRA, GpsFixType.Fix2d)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().vehicle?.shown shouldBeEqualTo PositionShown.NotVerified
        }
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN a live position WHEN no position arrives for 1·5 s THEN draws it stale with its age`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = verified(CANBERRA)
            val observer = viewModel.state.testIn(backgroundScope)

            delay(ONE_AND_A_HALF_SECONDS)
            val result = observer.expectMostRecentItem()

            result.vehicle?.shown shouldBeEqualTo PositionShown.Stale(age = 2.seconds)
        }
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a live position WHEN no HEARTBEAT arrives for the timeout THEN draws it link lost aged as the banner`() =
        runTest {
            turbineScope {
                connect()
                activeTelemetry.value = verified(CANBERRA)
                val observer = viewModel.state.testIn(backgroundScope)

                delay(PAST_TIMEOUT)
                val result = observer.expectMostRecentItem()

                result.vehicle?.shown shouldBeEqualTo PositionShown.LinkLost(age = 3.seconds)
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the last HEARTBEAT before the last position WHEN the timeout passes THEN is link lost on time`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED, heardAgo = HALF_SECOND))
                activeTelemetry.value = verified(CANBERRA)
                val observer = viewModel.state.testIn(backgroundScope)

                delay(JUST_PAST_TIMEOUT_AFTER_HEARTBEAT)
                val result = observer.expectMostRecentItem()

                result.vehicle?.shown shouldBeEqualTo PositionShown.LinkLost(age = 3.seconds)
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a lost link WHEN a fresh verified position arrives THEN is never live nor younger than the banner`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.LOST, heardAgo = TIMEOUT))
                activeTelemetry.value = verified(CANBERRA)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().vehicle?.shown shouldBeEqualTo PositionShown.LinkLost(age = 3.seconds)
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN a position that stopped before the link was lost WHEN drawing it THEN shows its own older age`() =
        runTest {
            turbineScope {
                activeSystemId.value = SYSTEM_1
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.LOST, heardAgo = TIMEOUT))
                activeTelemetry.value = verified(CANBERRA, receivedAgo = TEN_SECONDS)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().vehicle?.shown shouldBeEqualTo PositionShown.LinkLost(age = TEN_SECONDS)
            }
        }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the link lost WHEN whole seconds pass THEN the age steps with each`() = runTest {
        turbineScope {
            activeSystemId.value = SYSTEM_1
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.LOST, heardAgo = TIMEOUT))
            activeTelemetry.value = verified(CANBERRA, receivedAgo = TIMEOUT)
            val observer = viewModel.state.testIn(backgroundScope)

            val result = List(2) {
                delay(ONE_SECOND)
                observer.expectMostRecentItem().vehicle?.shown
            }

            result shouldBeEqualTo listOf(PositionShown.LinkLost(4.seconds), PositionShown.LinkLost(5.seconds))
        }
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN a stale position WHEN whole seconds pass THEN its age steps with each`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = verified(CANBERRA)
            val observer = viewModel.state.testIn(backgroundScope)

            val result = listOf(ONE_AND_A_HALF_SECONDS, ONE_SECOND).map { wait ->
                delay(wait)
                observer.expectMostRecentItem().vehicle?.shown
            }

            result shouldBeEqualTo listOf(PositionShown.Stale(2.seconds), PositionShown.Stale(3.seconds))
        }
    }

    @Tag("REQ-029")
    @Test
    fun `GIVEN a fresh position WHEN the GPS stops reporting THEN is not verified on time`() = runTest {
        turbineScope {
            connect()
            val gpsReport = verified(CANBERRA)
            activeTelemetry.value = gpsReport
            val observer = viewModel.state.testIn(backgroundScope)
            delay(ONE_AND_A_HALF_SECONDS)
            activeTelemetry.value = gpsReport.copy(position = Timed(CANBERRA, testScheduler.timeSource.markNow()))

            delay(JUST_PAST_GPS_STALE)
            val result = observer.expectMostRecentItem()

            result.vehicle?.shown shouldBeEqualTo PositionShown.NotVerified
        }
    }

    @Test
    fun `GIVEN ArduPilot's 0°, 0° before a position WHEN observing the map THEN draws no vehicle`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = verified(NULL_ISLAND)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo MapUiState()
        }
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN telemetry from another session than the vehicle WHEN observing the map THEN draws none of it`() =
        runTest {
            turbineScope {
                connect()
                activeTelemetry.value = verified(CANBERRA, home = HOME, origin = DataOrigin.REPLAY)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo MapUiState()
            }
        }

    @Test
    fun `GIVEN the camera following WHEN the operator moves the map THEN stops following`() = runTest {
        turbineScope {
            val observer = viewModel.state.testIn(backgroundScope)

            viewModel.onMapPanned()
            val result = observer.expectMostRecentItem()

            result.following shouldBeEqualTo false
        }
    }

    @Test
    fun `GIVEN the camera left by the operator WHEN recentering THEN follows the vehicle again`() = runTest {
        turbineScope {
            val observer = viewModel.state.testIn(backgroundScope)
            viewModel.onMapPanned()

            viewModel.onRecenter()
            val result = observer.expectMostRecentItem()

            result.following shouldBeEqualTo true
        }
    }

    @Test
    fun `GIVEN the map no longer observed WHEN reading its state THEN nothing remembered is shown`() = runTest {
        turbineScope {
            connect()
            activeTelemetry.value = verified(CANBERRA)
            val observer = viewModel.state.testIn(backgroundScope)
            observer.expectMostRecentItem()

            observer.cancel()
            delay(PAST_STOP_TIMEOUT)
            val result = viewModel.state.value

            result shouldBeEqualTo MapUiState()
        }
    }

    private fun TestScope.connect() {
        activeSystemId.value = SYSTEM_1
        vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED, heardAgo = Duration.ZERO))
    }

    private fun TestScope.vehicle(link: VehicleLink, heardAgo: Duration) = Vehicle(
        state = VehicleState(SYSTEM_1, VehicleType.COPTER, FlightMode.Named("Guided"), armed = true),
        link = link,
        lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
        origin = DataOrigin.LIVE,
    )

    private fun TestScope.verified(
        position: GlobalPosition,
        home: Home? = null,
        origin: DataOrigin = DataOrigin.LIVE,
        receivedAgo: Duration = Duration.ZERO,
    ): Telemetry = withFix(position, GpsFixType.Fix3d, home, origin, receivedAgo)

    private fun TestScope.withFix(
        position: GlobalPosition,
        fixType: GpsFixType,
        home: Home? = null,
        origin: DataOrigin = DataOrigin.LIVE,
        receivedAgo: Duration = Duration.ZERO,
    ): Telemetry {
        val now = testScheduler.timeSource.markNow() - receivedAgo
        return Telemetry(
            origin = origin,
            position = Timed(position, now),
            gps = Timed(gps(fixType), now),
            home = home?.let { Timed(it, now) },
        )
    }
}

private val SYSTEM_1 = SystemId(1)
private val TIMEOUT = LinkLossRule.DEFAULT_TIMEOUT
private val ONE_AND_A_HALF_SECONDS = 1_500.milliseconds
private val PAST_TIMEOUT = TIMEOUT + 500.milliseconds
private val HALF_SECOND = 500.milliseconds
private val ONE_SECOND = 1.seconds
private val TEN_SECONDS = 10.seconds

/** 0·6 s after the position update at 1·5 s, so 2·1 s after the GPS report: past its 2 s threshold, clear of its edge. */
private val JUST_PAST_GPS_STALE = 600.milliseconds

/** The timeout counted from a HEARTBEAT half a second older than the position, off every whole second of its age. */
private val JUST_PAST_TIMEOUT_AFTER_HEARTBEAT = TIMEOUT - HALF_SECOND + 100.milliseconds
private val PAST_STOP_TIMEOUT = 5.seconds + 1.milliseconds

private const val HEADING = 169.0
private const val ALTITUDE_ABOVE_HOME = 42.3

/** ArduPilot SITL's home, Canberra. */
private val CANBERRA = position(latitude = -35.3633514, longitude = 149.1652411)
private val HOME = Home(latitude = -35.3632621, longitude = 149.1652374, altitudeMsl = 584.09)

/** What ArduPilot SITL sent before its first GPS fix (#42). */
private val NULL_ISLAND = position(latitude = 0.0, longitude = 0.0)

private val TRAIL_A = TrailPoint(-35.3633514, 149.1652411)
private val TRAIL_B = TrailPoint(-35.3633514, 149.1652643)
private val TRAIL_C = TrailPoint(-35.3633514, 149.1653107)

private val TrailPoint.mapPoint get() = MapPoint(latitude, longitude)

private fun mapVehicle(shown: PositionShown) = MapVehicle(
    systemId = SYSTEM_1.value,
    position = MapPoint(CANBERRA.latitude, CANBERRA.longitude),
    heading = HEADING,
    altitudeAboveHome = ALTITUDE_ABOVE_HOME,
    shown = shown,
)

private fun position(latitude: Double, longitude: Double) = GlobalPosition(
    latitude = latitude,
    longitude = longitude,
    altitudeMsl = 626.4,
    altitudeAboveHome = ALTITUDE_ABOVE_HOME,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = HEADING,
)

private fun gps(fixType: GpsFixType) = GpsStatus(
    fixType = fixType,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)
