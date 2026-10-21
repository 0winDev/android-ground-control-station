package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.replay.ReplayClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import com.owindev.gcs.data.vehicle.telemetry.TelemetryTarget
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The vehicle repository across a change of link session (REQ-037), with the real session and a telemetry job that
 * never consumes its targets, so whatever the watchdog queues stays queued until a session change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-037")
class SessionChangeTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val targets = mutableListOf<Flow<TelemetryTarget?>>()
    private val activeVehicle = ActiveVehicle()
    private val session = LinkSession(
        live = transport,
        scope = testScope.backgroundScope,
        timeSource = testScope.testScheduler.timeSource,
        pipelineClock = PipelineClock(testScope.testScheduler.timeSource),
        recorder = ignoringRecorder(),
        activeVehicle = activeVehicle,
    )

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        repository = DefaultVehicleRepository(
            session = session,
            timeSource = testScope.testScheduler.timeSource,
            rule = LinkLossRule(),
            heartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder(), session)),
            telemetryRequester = telemetryRequester,
            telemetryStore = TelemetryStore(),
            activeVehicle = activeVehicle,
            scope = testScope.backgroundScope,
        )
    }

    @Test
    fun `GIVEN a request target still queued WHEN a replay comes and goes THEN the live link gets none of it`() =
        testScope.runTest {
            coEvery { telemetryRequester.run(capture(targets), any()) } coAnswers { awaitCancellation() }
            coEvery { transport.send(any(), any()) } returns SendResult.Sent
            every { transport.datagrams() } returns liveSocketWith(HEARTBEAT)
            repository.vehicles.launchIn(backgroundScope)
            delay(HEARD)
            session.replay(emptySource(), ReplayClock(testScheduler.timeSource))
            runCurrent()
            session.live()
            runCurrent()

            val result = targets.last().first()

            result shouldBeEqualTo null
        }
}

private fun liveSocketWith(fixture: String): Flow<Datagram> = flow {
    emit(Datagram(readFixture(fixture), VEHICLE_ADDRESS))
    awaitCancellation()
}

private fun emptySource(): DatagramSource = object : DatagramSource {
    override fun datagrams(): Flow<Datagram> = emptyFlow()
}

private const val HEARTBEAT = "heartbeat/disarmed-stabilize.bin"
private const val HEARD = 500L
private val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
