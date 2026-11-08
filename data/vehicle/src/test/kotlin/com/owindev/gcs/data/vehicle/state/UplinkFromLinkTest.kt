package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The uplink record across the active vehicle's link (REQ-045), with real SITL HEARTBEATs through the whole pipeline
 * in virtual time, as in [DefaultVehicleRepositoryTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-045")
class UplinkFromLinkTest {

    private val testScope = TestScope()
    private val transport: UdpTransport = mockk()
    private val telemetryRequester: TelemetryRequester = mockk()
    private val uplink = DefaultUplinkRepository(testScope.testScheduler.timeSource)

    private lateinit var repository: DefaultVehicleRepository

    @BeforeEach
    fun setUp() {
        repository = testScope.repositoryOn(
            transport,
            telemetryRequester,
            TelemetryStore(TrailRule(StalenessRule()), ActiveVehicle()),
            uplink = uplink,
        )
    }

    @Test
    fun `GIVEN a command without answer WHEN the vehicle is LOST for 50 ms THEN the uplink is healthy`() =
        testScope.runTest {
            coEvery { transport.send(any(), any()) } returns SendResult.Sent
            coEvery { telemetryRequester.run(any(), any()) } coAnswers { awaitCancellation() }
            every { transport.datagrams() } returns datagrams(
                GAP to DISARMED_STABILIZE,
                BRIEFLY_PAST_TIMEOUT to DISARMED_STABILIZE,
            )
            repository.vehicles.launchIn(backgroundScope)
            delay(HEARD)
            uplink.commandUnanswered()

            delay(UNTIL_HEARD_AGAIN)

            uplink.health.value shouldBeEqualTo UplinkHealth.HEALTHY
        }
}

private const val HEARD = 1_500L

// LOST at 3.1 s, heard again at 3.15 s: between two ticks of the 1 Hz GCS HEARTBEAT loop, which never sees it.
private const val BRIEFLY_PAST_TIMEOUT = 3_050L
private const val UNTIL_HEARD_AGAIN = 2_000L
