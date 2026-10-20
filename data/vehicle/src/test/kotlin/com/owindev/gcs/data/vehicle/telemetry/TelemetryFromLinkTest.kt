package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real SITL frames through the whole pipeline (transport → parser → decoder → the vehicle repository's
 * listener → [TelemetryStore]) in virtual time, the repository's clock being the test scheduler's. What each
 * frame maps to is covered in [TelemetryMapperTest]; here, that it lands under its system ID with the time it
 * arrived, that it never keeps a vehicle alive, and when the telemetry job may request a silent stream again.
 */
class TelemetryFromLinkTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val isHeard: CapturingSlot<() -> Boolean> = slot()
    private val store = TelemetryStore()
    private val activeVehicle = ActiveVehicle()

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        coEvery { transport.send(any(), any()) } returns SendResult.Sent
        coEvery { telemetryRequester.run(any(), capture(isHeard)) } coAnswers { awaitCancellation() }
        repository = DefaultVehicleRepository(
            session = LinkSession(
                transport = transport,
                scope = testScope.backgroundScope,
                timeSource = testScope.testScheduler.timeSource,
                recorder = ignoringRecorder(),
                activeVehicle = activeVehicle,
            ),
            timeSource = testScope.testScheduler.timeSource,
            rule = LinkLossRule(),
            heartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder())),
            telemetryRequester = telemetryRequester,
            telemetryStore = store,
            activeVehicle = activeVehicle,
            scope = testScope.backgroundScope,
        )
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN real SITL telemetry WHEN observing the vehicles THEN is kept for its system ID, marked on arrival`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(HEARTBEAT, ATTITUDE)
            repository.vehicles.launchIn(backgroundScope)

            delay(GAP + GAP + SINCE_ARRIVAL)
            val result = store.telemetry.value.mapValues { (_, telemetry) ->
                telemetry.attitude?.receivedAt?.elapsedNow()
            }

            result shouldBeEqualTo mapOf(SystemId(1) to SINCE_ARRIVAL.milliseconds)
        }

    @Tag("REQ-026")
    @Test
    fun `GIVEN real SITL telemetry WHEN nobody observes the vehicles THEN nothing is kept`() = testScope.runTest {
        every { transport.datagrams() } returns datagrams(HEARTBEAT, ATTITUDE)

        delay(GAP + GAP + SINCE_ARRIVAL)
        val result = store.telemetry.value

        result shouldBeEqualTo emptyMap()
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN one HEARTBEAT and then only telemetry WHEN observing the vehicles THEN is LOST at the timeout`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(HEARTBEAT, afterwards = everySecond(ATTITUDE))
            val start = testScheduler.timeSource.markNow()

            repository.vehicles.first { vehicles -> vehicles.values.any { it.link == VehicleLink.LOST } }
            val result = start.elapsedNow()

            result shouldBeEqualTo (GAP + TIMEOUT).milliseconds
        }

    @Tag("REQ-023")
    @Test
    fun `GIVEN the vehicles observed and the vehicle CONNECTED WHEN the telemetry job asks THEN it is heard`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(afterwards = everySecond(HEARTBEAT))
            repository.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)

            val result = isHeard.captured()

            result.shouldBeTrue()
        }

    @Tag("REQ-023")
    @Test
    fun `GIVEN the link closed with the vehicle still CONNECTED WHEN the telemetry job asks THEN it is not heard`() =
        testScope.runTest {
            every { transport.datagrams() } returns datagrams(afterwards = everySecond(HEARTBEAT))
            val observer = repository.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)
            observer.cancel()
            delay(AFTER_LINK_CLOSED)

            val result = isHeard.captured() to repository.vehicles.value.getValue(SystemId(1)).link

            result shouldBeEqualTo (false to VehicleLink.CONNECTED)
        }

    @Tag("REQ-023")
    @Test
    fun `GIVEN the device slept past the timeout WHEN the telemetry job asks THEN it is not heard`() =
        testScope.runTest {
            val deviceClock = TestTimeSource()
            every { transport.datagrams() } returns datagrams(HEARTBEAT)
            val sleepy = DefaultVehicleRepository(
                session = LinkSession(
                    transport = transport,
                    scope = backgroundScope,
                    timeSource = testScheduler.timeSource,
                    recorder = ignoringRecorder(),
                    activeVehicle = activeVehicle,
                ),
                timeSource = deviceClock,
                rule = LinkLossRule(),
                heartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder())),
                telemetryRequester = telemetryRequester,
                telemetryStore = store,
                activeVehicle = activeVehicle,
                scope = backgroundScope,
            )
            sleepy.vehicles.launchIn(backgroundScope)
            delay(OBSERVED_BRIEFLY)

            // The device slept: its clock moved past the timeout while the watchdog's timer barely did.
            deviceClock += TIMEOUT.milliseconds
            val result = isHeard.captured() to sleepy.vehicles.value.getValue(SystemId(1)).link

            result shouldBeEqualTo (false to VehicleLink.CONNECTED)
        }
}

private const val HEARTBEAT = "heartbeat/disarmed-stabilize.bin"
private const val ATTITUDE = "telemetry/attitude.bin"
private const val GAP = 100L
private const val SINCE_ARRIVAL = 1_000L
private const val TIMEOUT = 3_000L
private const val ONE_SECOND = 1_000L
private const val OBSERVED_BRIEFLY = 1_200L

// The listener stops 5 s (LINK_STOP_TIMEOUT) after the observer leaves at 1.2 s; a HEARTBEAT arrived at 6 s,
// so at 7.5 s the link is closed and the vehicle is still CONNECTED.
private const val AFTER_LINK_CLOSED = 6_300L

private val SYSTEM_1_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)

/** Each fixture [GAP] ms after the previous one, from system 1's address, then [afterwards]. */
private fun datagrams(
    vararg fixtures: String,
    afterwards: Flow<Datagram> = flow { awaitCancellation() },
): Flow<Datagram> = flow {
    fixtures.forEach { fixture ->
        delay(GAP)
        emit(datagramOf(fixture))
    }
    afterwards.collect { emit(it) }
}

/** [fixture] every second, forever, starting one second from now. */
private fun everySecond(fixture: String): Flow<Datagram> = flow {
    while (true) {
        delay(ONE_SECOND)
        emit(datagramOf(fixture))
    }
}

private fun datagramOf(fixture: String) = Datagram(payload = readFixture(fixture), sender = SYSTEM_1_ADDRESS)
