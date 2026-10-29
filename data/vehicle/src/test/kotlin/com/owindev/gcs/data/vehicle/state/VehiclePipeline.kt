package com.owindev.gcs.data.vehicle.state

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
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

/** The repository under test on [transport], in this scope's virtual time; each test passes only what it changes. */
// Mirrors the repository's constructor, one parameter each.
@Suppress("LongParameterList")
internal fun TestScope.repositoryOn(
    transport: UdpTransport,
    telemetryRequester: TelemetryRequester,
    telemetryStore: TelemetryStore,
    activeVehicle: ActiveVehicle = ActiveVehicle(),
    session: LinkSession = sessionOn(transport, activeVehicle),
    timeSource: TimeSource.WithComparableMarks = testScheduler.timeSource,
    rule: LinkLossRule = LinkLossRule(),
    heartbeatSender: GcsHeartbeatSender = GcsHeartbeatSender(GcsMessageSender(transport, ignoringRecorder(), session)),
    scope: CoroutineScope = backgroundScope,
) = DefaultVehicleRepository(
    session = session,
    timeSource = timeSource,
    rule = rule,
    heartbeatSender = heartbeatSender,
    telemetryRequester = telemetryRequester,
    telemetryStore = telemetryStore,
    activeVehicle = activeVehicle,
    scope = scope,
)

/** A link session on [transport] in this scope's virtual time, recording nothing. */
internal fun TestScope.sessionOn(transport: UdpTransport, activeVehicle: ActiveVehicle) = LinkSession(
    live = transport,
    scope = backgroundScope,
    timeSource = testScheduler.timeSource,
    pipelineClock = PipelineClock(testScheduler.timeSource),
    recorder = ignoringRecorder(),
    activeVehicle = activeVehicle,
)

internal const val DISARMED_STABILIZE = "heartbeat/disarmed-stabilize.bin"
internal const val DISARMED_LOITER = "heartbeat/disarmed-loiter.bin"
internal const val SYSTEM_2_LOITER = "derived/heartbeat-sysid-2.bin"
internal const val GCS = "gcs/heartbeat-mission-planner.bin"

// Virtual milliseconds. The default timeout is 3 s (REQ-017).
internal const val GAP = 100L
internal const val AFTER_TWO_GAPS = 300L
internal const val WELL_PAST_TIMEOUT = 4_000L

internal val SYSTEM_1 = SystemId(1)
internal val SYSTEM_2 = SystemId(2)
internal val SYSTEM_1_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
internal val SYSTEM_2_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_561)
internal val GCS_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_570)

/**
 * One fixture per datagram, each after its gap in virtual milliseconds; then [afterwards] runs. By default
 * the socket stays open with nothing more arriving, as when the vehicle goes silent.
 */
internal fun datagrams(
    vararg fixtures: Pair<Long, String>,
    afterwards: suspend FlowCollector<Datagram>.() -> Unit = { awaitCancellation() },
): Flow<Datagram> = flow {
    fixtures.forEach { (gap, fixture) ->
        delay(gap)
        emit(datagramOf(fixture))
    }
    afterwards()
}

/** [fixture] as the transport delivers it: system 2 and the ground station each send from their own address. */
internal fun datagramOf(fixture: String) = Datagram(
    payload = readFixture(fixture),
    sender = when (fixture) {
        SYSTEM_2_LOITER -> SYSTEM_2_ADDRESS
        GCS -> GCS_ADDRESS
        else -> SYSTEM_1_ADDRESS
    },
)
