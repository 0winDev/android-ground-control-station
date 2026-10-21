package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.testing.readFixture
import com.owindev.gcs.core.transport.Datagram
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.ignoringRecorder
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The active vehicle pinned by the receive loop and read by the vehicle repository (REQ-020), through the real
 * session: the pin does not wait for the repository to list the vehicle.
 */
class ActiveVehicleFromLinkTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
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
        coEvery { telemetryRequester.run(any(), any()) } coAnswers { awaitCancellation() }
        repository = DefaultVehicleRepository(
            session = session,
            timeSource = testScope.testScheduler.timeSource,
            rule = LinkLossRule(),
            heartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder(), session)),
            telemetryRequester = telemetryRequester,
            telemetryStore = TelemetryStore(TrailRule(StalenessRule())),
            activeVehicle = activeVehicle,
            scope = testScope.backgroundScope,
        )
    }

    @Tag("REQ-020")
    @Test
    fun `GIVEN only another reader holds the link open WHEN a vehicle is heard THEN is active before listed`() =
        testScope.runTest {
            every { transport.datagrams() } returns flowOf(Datagram(readFixture(HEARTBEAT), SENDER))

            session.messages.first()

            repository.activeSystemId.value shouldBeEqualTo SystemId(1)
            repository.vehicles.value shouldBeEqualTo emptyMap()
        }

    private companion object {
        const val HEARTBEAT = "heartbeat/disarmed-stabilize.bin"
        val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
    }
}
