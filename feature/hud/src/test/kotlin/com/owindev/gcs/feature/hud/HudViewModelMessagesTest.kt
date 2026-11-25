package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.domain.link.UplinkRule
import com.owindev.gcs.core.domain.message.MessageRepository
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.ShownMessageRule
import com.owindev.gcs.core.domain.message.VehicleMessage
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The vehicle's messages on the HUD (REQ-047), on the test scheduler's clock as in [HudViewModelTest]. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-047")
class HudViewModelMessagesTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS))
    private val activeSystemId = MutableStateFlow<SystemId?>(SYSTEM_1)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val messages = MutableStateFlow<List<VehicleMessage>>(emptyList())
    private val linkRepository: LinkRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val alertRepository: AlertRepository = mockk()
    private val messageRepository: MessageRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        every { vehicleRepository.activeSystemId } returns activeSystemId
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns MutableStateFlow<Telemetry?>(null)
        every { alertRepository.activeAlerts } returns MutableStateFlow<Alerts>(Alerts.NotEvaluated)
        every { messageRepository.activeMessages } returns messages
        viewModel = HudViewModel(
            linkRepository,
            vehicleRepository,
            telemetryRepository,
            alertRepository,
            healthyUplink(),
            UplinkRule(),
            messageRepository,
            ShownMessageRule(),
            LinkLossRule(),
            StalenessRule(),
            DeviceClock(mainDispatcher.testDispatcher.scheduler.timeSource),
        )
    }

    @Test
    fun `GIVEN a heard vehicle WHEN it sent a warning 2300 ms ago THEN it is at a glance as a caution aged 3 s`() =
        runTest {
            turbineScope {
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
                messages.value = listOf(message(MessageSeverity.WARNING, GPS_TEXT, ago = 2300.milliseconds))

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().messages().latest shouldBeEqualTo line(
                    GPS_TEXT,
                    AlertLevel.CAUTION,
                    age = 3.seconds,
                )
            }
        }

    @Test
    fun `GIVEN a heard vehicle WHEN it sent three messages THEN all are listed newest first`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(
                message(MessageSeverity.INFO, NEWEST_TEXT),
                message(MessageSeverity.WARNING, GPS_TEXT),
                message(MessageSeverity.CRITICAL, PRE_ARM_TEXT),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().messages().kept shouldBeEqualTo listOf(
                line(NEWEST_TEXT, AlertLevel.ADVISORY),
                line(GPS_TEXT, AlertLevel.CAUTION),
                line(PRE_ARM_TEXT, AlertLevel.EMERGENCY),
            )
        }
    }

    @Test
    fun `GIVEN an emergency at a glance WHEN a newer advisory comes THEN the emergency stays at a glance`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            val emergency = message(MessageSeverity.CRITICAL, PRE_ARM_TEXT)
            messages.value = listOf(emergency)
            val state = viewModel.state.testIn(backgroundScope)
            runCurrent()

            messages.value = listOf(message(MessageSeverity.INFO, NEWEST_TEXT), emergency)
            runCurrent()

            state.expectMostRecentItem().messages().latest?.text shouldBeEqualTo PRE_ARM_TEXT
        }
    }

    @Test
    fun `GIVEN an emergency held at a glance WHEN its 30 s hold ends between two seconds THEN it is replaced then`() =
        runTest {
            turbineScope {
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
                val emergency = message(MessageSeverity.CRITICAL, PRE_ARM_TEXT)
                messages.value = listOf(emergency)
                val state = viewModel.state.testIn(backgroundScope)
                advanceTimeBy(ADVISORY_LATER)
                messages.value = listOf(message(MessageSeverity.INFO, NEWEST_TEXT, ago = Duration.ZERO), emergency)
                runCurrent()

                advanceTimeBy(SERIOUS_HOLD - HALF_SECOND - ADVISORY_LATER)
                runCurrent()
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
                runCurrent()

                state.expectMostRecentItem().messages().latest?.text shouldBeEqualTo NEWEST_TEXT
            }
        }

    @Test
    fun `GIVEN an emergency at a glance WHEN 100 newer advisories push it out of the list THEN it stays`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            val emergency = message(MessageSeverity.CRITICAL, PRE_ARM_TEXT)
            messages.value = listOf(emergency)
            val state = viewModel.state.testIn(backgroundScope)
            runCurrent()

            messages.value = (1..KEPT).map { message(MessageSeverity.INFO, "$NEWEST_TEXT $it") }
            runCurrent()

            state.expectMostRecentItem().messages().latest?.text shouldBeEqualTo PRE_ARM_TEXT
        }
    }

    @Test
    fun `GIVEN an emergency at a glance WHEN another vehicle becomes active THEN its own newest is at a glance`() =
        runTest {
            turbineScope {
                vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
                messages.value = listOf(message(MessageSeverity.CRITICAL, PRE_ARM_TEXT))
                val state = viewModel.state.testIn(backgroundScope)
                runCurrent()

                vehicles.value = mapOf(SYSTEM_2 to vehicle(VehicleLink.CONNECTED, systemId = SYSTEM_2))
                activeSystemId.value = SYSTEM_2
                messages.value = listOf(message(MessageSeverity.INFO, NEWEST_TEXT))
                runCurrent()

                state.expectMostRecentItem().messages().latest?.text shouldBeEqualTo NEWEST_TEXT
            }
        }

    @Test
    fun `GIVEN a message at a glance WHEN a second passes THEN its age and its row's count on together`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(message(MessageSeverity.WARNING, GPS_TEXT, ago = 2300.milliseconds))
            val state = viewModel.state.testIn(backgroundScope)
            runCurrent()

            advanceTimeBy(1.seconds)
            runCurrent()

            val result = state.expectMostRecentItem().messages()
            listOf(result.latest?.age, result.kept.single().age) shouldBeEqualTo listOf(4.seconds, 4.seconds)
        }
    }

    @Test
    fun `GIVEN a severity outside MAV_SEVERITY WHEN shown THEN it is an advisory with its value`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(
                message(MessageSeverity.UNKNOWN, GPS_TEXT).copy(severityValue = UNKNOWN_SEVERITY),
            )

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().messages().latest shouldBeEqualTo line(
                GPS_TEXT,
                AlertLevel.ADVISORY,
                unknownSeverity = UNKNOWN_SEVERITY,
            )
        }
    }

    @Test
    fun `GIVEN a text with a line break and a bidirectional override WHEN shown THEN it is sanitised`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(message(MessageSeverity.WARNING, "GPS\nBad\u202Efix"))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().messages().latest?.text shouldBeEqualTo "GPS Bad\uFFFDfix"
        }
    }

    @Test
    fun `GIVEN a message chunk never came WHEN shown THEN it is marked incomplete`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(message(MessageSeverity.WARNING, GPS_TEXT).copy(incomplete = true))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().messages().latest?.incomplete shouldBeEqualTo true
        }
    }

    @Test
    @Tag("REQ-037")
    fun `GIVEN a recording playing WHEN its vehicle sent a message THEN it is marked recorded`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Replaying(datagrams = DATAGRAMS)
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED).copy(origin = DataOrigin.REPLAY))
            messages.value = listOf(message(MessageSeverity.WARNING, GPS_TEXT).copy(origin = DataOrigin.REPLAY))

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().messages().latest?.recorded shouldBeEqualTo true
        }
    }

    @Test
    @Tag("REQ-017")
    fun `GIVEN messages kept WHEN the link is lost THEN none is at a glance but all can still be read`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.LOST, heardAgo = LOST_FOR))
            messages.value = listOf(message(MessageSeverity.CRITICAL, PRE_ARM_TEXT))

            val result = viewModel.state.testIn(backgroundScope)

            (result.expectMostRecentItem() as HudUiState.LinkLost).messages shouldBeEqualTo VehicleMessages(
                latest = null,
                kept = listOf(line(PRE_ARM_TEXT, AlertLevel.EMERGENCY)),
            )
        }
    }

    @Test
    @Tag("REQ-017")
    fun `GIVEN a message at a glance WHEN the link is lost and back THEN it is at a glance again`() = runTest {
        turbineScope {
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            messages.value = listOf(message(MessageSeverity.WARNING, GPS_TEXT))
            val state = viewModel.state.testIn(backgroundScope)
            runCurrent()
            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.LOST, heardAgo = LOST_FOR))
            runCurrent()

            vehicles.value = mapOf(SYSTEM_1 to vehicle(VehicleLink.CONNECTED))
            runCurrent()

            state.expectMostRecentItem().messages().latest?.text shouldBeEqualTo GPS_TEXT
        }
    }

    private fun HudUiState.messages(): VehicleMessages = (this as HudUiState.Connected).messages

    private fun TestScope.message(severity: MessageSeverity, text: String, ago: Duration = HALF_SECOND) =
        VehicleMessage(
            severity = severity,
            text = text,
            receivedAt = testScheduler.timeSource.markNow() - ago,
            origin = DataOrigin.LIVE,
        )

    private fun line(text: String, level: AlertLevel, age: Duration = 1.seconds, unknownSeverity: Int? = null) =
        MessageLine(
            text = text,
            level = level,
            unknownSeverity = unknownSeverity,
            age = age,
            incomplete = false,
            recorded = false,
        )

    private fun TestScope.vehicle(
        link: VehicleLink,
        heardAgo: Duration = Duration.ZERO,
        systemId: SystemId = SYSTEM_1,
    ) = Vehicle(
        state = VehicleState(systemId = systemId, type = VehicleType.COPTER, flightMode = GUIDED, armed = false),
        link = link,
        lastHeartbeat = testScheduler.timeSource.markNow() - heardAgo,
        origin = DataOrigin.LIVE,
    )

    private companion object {
        const val PORT = 14550
        const val DATAGRAMS = 42L
        const val UNKNOWN_SEVERITY = 9
        const val KEPT = 100
        const val GPS_TEXT = "GPS 1: detected u-blox"
        const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"
        const val NEWEST_TEXT = "Frame: QUAD/X"
        val SYSTEM_1 = SystemId(1)
        val SYSTEM_2 = SystemId(2)
        val GUIDED = FlightMode.Named("Guided")
        val LOST_FOR = 5.seconds
        val HALF_SECOND = 500.milliseconds
        val SERIOUS_HOLD = 30.seconds
        val ADVISORY_LATER = 200.milliseconds
    }
}
