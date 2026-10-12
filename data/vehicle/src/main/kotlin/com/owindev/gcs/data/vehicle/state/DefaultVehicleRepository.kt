package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.LINK_STOP_TIMEOUT
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryTarget
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps every vehicle heard on the shared [LinkSession], keyed by system ID (REQ-016), and watches each
 * one's link (REQ-017, REQ-019).
 *
 * Four jobs in the application scope, all started on first use:
 * - the **listener** collects HEARTBEATs only while [vehicles] is observed (plus [LINK_STOP_TIMEOUT]),
 *   so the socket is not kept open for nobody;
 * - the **watchdog** owns the map and the per-vehicle timers, and runs for as long as the app does. A
 *   vehicle therefore goes [VehicleLink.LOST] on time even when nobody observes it, when the transport
 *   fails or when the message flow ends: it can never be left [VehicleLink.CONNECTED] without HEARTBEATs,
 *   and a new observer never sees a stale CONNECTED;
 * - the **heartbeat** job ([GcsHeartbeatSender]) announces this GCS to the active vehicle, at the address
 *   its HEARTBEATs come from, only while that vehicle is CONNECTED (REQ-021): once it is lost, or the
 *   socket closes because nobody observes the link, the vehicle stops hearing this GCS and its own GCS
 *   failsafe can act. It only reads the current state, so it never keeps the link open by itself;
 * - the **telemetry** job ([TelemetryRequester]) asks the active vehicle for the HUD's telemetry each time
 *   it becomes CONNECTED (REQ-023), and never any other system ID. It only listens for answers while it
 *   is waiting for one.
 *
 * The first vehicle heard becomes the [activeSystemId] and stays so (REQ-020); the others are kept in the
 * map like any vehicle, never merged into it.
 *
 * The watchdog is the only writer: HEARTBEATs and timeouts reach it as events through one channel and
 * are handled one at a time, so no state is shared between threads. Vehicles are never removed: a lost
 * one keeps its last known state and comes back CONNECTED with its next HEARTBEAT.
 *
 * If the watchdog ever stops (an unexpected exception, or its scope being cancelled), it fails safe: every
 * vehicle goes LOST and the listener stops. An unexpected exception then reaches the application scope,
 * which has no handler, so the process crashes on purpose rather than keep showing links nobody watches.
 *
 * Time comes from the injected [timeSource]; on a device it keeps counting while the CPU sleeps, so a
 * vehicle's age is right after waking. Timers use coroutine `delay`, which pauses during sleep, so the
 * LOST transition itself can lag by up to one timeout after the device wakes.
 */
@Singleton
internal class DefaultVehicleRepository @Inject constructor(
    private val session: LinkSession,
    private val timeSource: TimeSource.WithComparableMarks,
    private val rule: LinkLossRule,
    private val heartbeatSender: GcsHeartbeatSender,
    private val telemetryRequester: TelemetryRequester,
    @param:ApplicationScope private val scope: CoroutineScope,
) : VehicleRepository {

    private val mutableVehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val mutableActiveSystemId = MutableStateFlow<SystemId?>(null)
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Where each vehicle's last HEARTBEAT came from; written only by the watchdog. */
    @Volatile
    private var addresses: Map<SystemId, InetSocketAddress> = emptyMap()

    override val vehicles: StateFlow<Map<SystemId, Vehicle>> by lazy {
        scope.launch { watch() }
        scope.launch { listenWhileObserved() }
        scope.launch { heartbeatSender.run(::activeAddressWhileConnected) }
        scope.launch { telemetryRequester.run(activeTargetWhileConnected()) }
        mutableVehicles.asStateFlow()
    }

    override val activeSystemId: StateFlow<SystemId?> by lazy {
        // Starts the jobs: the active vehicle comes from the same HEARTBEATs as the map.
        vehicles
        mutableActiveSystemId.asStateFlow()
    }

    private suspend fun listenWhileObserved() {
        SharingStarted.WhileSubscribed(LINK_STOP_TIMEOUT)
            .command(mutableVehicles.subscriptionCount)
            .distinctUntilChanged()
            .collectLatest { command ->
                if (command == SharingCommand.START) {
                    session.messages
                        .mapNotNull { received ->
                            HeartbeatMapper.vehicleStateOf(received)?.let { state ->
                                // Marked on arrival, not when the watchdog gets to it.
                                Event.Heard(state, timeSource.markNow(), received.sender)
                            }
                        }
                        .collect { heard -> events.send(heard) }
                }
            }
    }

    private suspend fun watch() = coroutineScope {
        val timers = mutableMapOf<SystemId, Job>()

        fun startTimer(systemId: SystemId, after: Duration) {
            timers.remove(systemId)?.cancel()
            timers[systemId] = launch {
                delay(after)
                events.send(Event.TimedOut(systemId))
            }
        }

        try {
            for (event in events) {
                when (event) {
                    is Event.Heard -> {
                        val systemId = event.state.systemId
                        val elapsed = event.at.elapsedNow()
                        // Normally CONNECTED; LOST only if the watchdog got to this HEARTBEAT after the
                        // timeout had already passed, so a late event never flashes CONNECTED.
                        val link = rule.linkAfter(elapsed)
                        // Pinned once, in arrival order, and never moved (REQ-020): a second vehicle on the
                        // link must not silently take the operator's place.
                        if (mutableActiveSystemId.value == null) mutableActiveSystemId.value = systemId
                        addresses += systemId to event.sender
                        mutableVehicles.value += systemId to Vehicle(event.state, link, event.at)
                        if (link == VehicleLink.CONNECTED) {
                            startTimer(systemId, rule.timeout - elapsed)
                        } else {
                            timers.remove(systemId)?.cancel()
                        }
                    }

                    is Event.TimedOut -> {
                        val vehicle = mutableVehicles.value[event.systemId] ?: continue
                        val elapsed = vehicle.lastHeartbeat.elapsedNow()
                        when {
                            vehicle.link == VehicleLink.LOST -> Unit

                            // The timeout is the only rule; a timer that fired early is simply restarted.
                            rule.linkAfter(elapsed) == VehicleLink.CONNECTED ->
                                startTimer(event.systemId, rule.timeout - elapsed)

                            else -> mutableVehicles.value += event.systemId to vehicle.copy(link = VehicleLink.LOST)
                        }
                    }
                }
            }
        } finally {
            // Whatever stopped the watchdog (a bug, or the app scope being cancelled), nobody watches the
            // links any more, so no vehicle may stay CONNECTED. Closing the channel makes the listener
            // fail loudly instead of queueing HEARTBEATs that nobody will handle.
            events.close()
            mutableVehicles.update { vehicles ->
                vehicles.mapValues { (_, vehicle) -> vehicle.copy(link = VehicleLink.LOST) }
            }
        }
    }

    /**
     * The active vehicle's address while it is CONNECTED, otherwise `null`. CONNECTED is checked twice, as
     * the HUD does: the watchdog's verdict and the rule applied to the age right now, because the watchdog's
     * timer can run late after the device sleeps (#12) and this GCS must not keep announcing itself then.
     */
    private fun activeAddressWhileConnected(): InetSocketAddress? {
        val systemId = mutableActiveSystemId.value
        val vehicle = systemId?.let(mutableVehicles.value::get)
        val connected = vehicle != null &&
            vehicle.link == VehicleLink.CONNECTED &&
            rule.linkAfter(vehicle.lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED
        return if (connected) addresses[systemId] else null
    }

    /**
     * The active vehicle and its address while it is CONNECTED, `null` otherwise; a new value each time it
     * becomes CONNECTED again. The watchdog writes the address before the vehicle, so it is always there.
     */
    private fun activeTargetWhileConnected(): Flow<TelemetryTarget?> =
        combine(mutableActiveSystemId, mutableVehicles) { systemId, vehicles ->
            systemId?.takeIf { vehicles[it]?.link == VehicleLink.CONNECTED }
        }
            .distinctUntilChanged()
            .map { systemId -> systemId?.let { TelemetryTarget(it, addresses.getValue(it)) } }

    private sealed interface Event {
        data class Heard(val state: VehicleState, val at: ComparableTimeMark, val sender: InetSocketAddress) : Event

        data class TimedOut(val systemId: SystemId) : Event
    }
}
