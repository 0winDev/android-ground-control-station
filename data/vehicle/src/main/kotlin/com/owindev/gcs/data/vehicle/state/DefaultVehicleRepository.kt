package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.time.atLeastRecheck
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.GcsHeartbeatSender
import com.owindev.gcs.data.vehicle.link.LINK_STOP_TIMEOUT
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.telemetry.TelemetryRequester
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps every vehicle heard on the shared [LinkSession], keyed by system ID (REQ-016), and watches each
 * one's link (REQ-017, REQ-019).
 *
 * Four jobs in the application scope, all started on first use:
 * - the **listener** collects HEARTBEATs only while [vehicles] is observed (plus [LINK_STOP_TIMEOUT]),
 *   so the socket is not kept open for nobody. It also keeps every vehicle's telemetry in the
 *   [TelemetryStore] (REQ-026), marked on arrival with the same clock as the HEARTBEATs;
 * - the **watchdog** owns the map and the per-vehicle timers, and runs for as long as the app does. A
 *   vehicle therefore goes [VehicleLink.LOST] on time even when nobody observes it, when the transport
 *   fails or when the message flow ends: it can never be left [VehicleLink.CONNECTED] without HEARTBEATs,
 *   and a new observer never sees a stale CONNECTED;
 * - the **heartbeat** job ([GcsHeartbeatSender]) announces this GCS to the active vehicle, at the address
 *   its HEARTBEATs come from, only while that vehicle is CONNECTED (REQ-021): once it is lost, or the
 *   socket closes because nobody observes the link, the vehicle stops hearing this GCS and its own GCS
 *   failsafe can act. It only reads the current state, so it never keeps the link open by itself;
 * - the **telemetry** job ([TelemetryRequester]) asks the active vehicle for its telemetry each time it
 *   becomes CONNECTED (REQ-023), and never any other system ID. The watchdog tells it of every change of the
 *   active vehicle's link, in order, so a LOST followed at once by a CONNECTED still starts a new round, and
 *   of a reboot the store detects while it stays CONNECTED, since the autopilot has forgotten the intervals
 *   (REQ-033). It only listens for answers while it is waiting for one.
 *
 * The [activeSystemId] is the [ActiveVehicle] the link pinned with the first vehicle heard, and stays so (REQ-020);
 * the others are kept in the map like any vehicle, never merged into it.
 *
 * The watchdog is the only writer: HEARTBEATs and timeouts reach it as events through one channel and
 * are handled one at a time, so no state is shared between threads. Vehicles are never removed: a lost
 * one keeps its last known state and comes back CONNECTED with its next HEARTBEAT.
 *
 * If the watchdog ever stops (an unexpected exception, or its scope being cancelled), it fails safe: every
 * vehicle goes LOST and the listener stops. An unexpected exception then reaches the application scope,
 * which has no handler, so the process crashes on purpose rather than keep showing links nobody watches.
 *
 * A replay (REQ-037) is a session of its own: when the link's [LinkSession.session] changes, the watchdog forgets every
 * vehicle, timer, address and request target, and the store its telemetry, so nothing live shows in a replay, nor one
 * replay in the next, nor a replay after it; a message from another session is dropped. The heartbeat and telemetry jobs only run on the live link, and only
 * the live active vehicle is ever a telemetry target, so a replay never transmits.
 *
 * Time comes from the injected [timeSource]; on a device it keeps counting while the CPU sleeps, so a
 * vehicle's age is right after waking. Timers use coroutine `delay`, which pauses during sleep, so the
 * LOST transition itself can lag by up to one timeout after the device wakes.
 */
// One collaborator per job above, plus the store the listener fills and the pin; grouping them would only hide that.
@Suppress("LongParameterList")
@Singleton
internal class DefaultVehicleRepository @Inject constructor(
    private val session: LinkSession,
    private val timeSource: TimeSource.WithComparableMarks,
    private val rule: LinkLossRule,
    private val heartbeatSender: GcsHeartbeatSender,
    private val telemetryRequester: TelemetryRequester,
    private val telemetryStore: TelemetryStore,
    private val activeVehicle: ActiveVehicle,
    @param:ApplicationScope private val scope: CoroutineScope,
) : VehicleRepository {

    private val mutableVehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val events = Channel<Event>(Channel.UNLIMITED)

    /**
     * Each change of the active vehicle's link, for the telemetry job: its target when it becomes CONNECTED or
     * reboots while CONNECTED, `null` when it is lost. A channel, not a state: two changes in a row must both
     * arrive, and there is a single consumer, so nothing is replayed to a late one.
     */
    private val activeLinkChanges = Channel<TelemetryTarget?>(Channel.UNLIMITED)

    /** Where each vehicle's last HEARTBEAT came from; written only by the watchdog. */
    @Volatile
    private var addresses: Map<SystemId, InetSocketAddress> = emptyMap()

    /** Whether the listener holds the link open right now; written only by the listener. */
    @Volatile
    private var listening = false

    override val vehicles: StateFlow<Map<SystemId, Vehicle>> by lazy {
        scope.launch { watch() }
        scope.launch { listenWhileObserved() }
        scope.launch { session.session.collect { current -> events.send(Event.SessionChanged(current)) } }
        scope.launch { whileLive { heartbeatSender.run(::activeAddressWhileConnected) } }
        scope.launch { whileLive { telemetryRequester.run(activeLinkChanges.receiveAsFlow(), ::activeHeardNow) } }
        mutableVehicles.asStateFlow()
    }

    /** Runs [job] while the link is the live one, never during a replay (REQ-037). */
    private suspend fun whileLive(job: suspend () -> Unit) {
        session.session.map { it.origin }.distinctUntilChanged().collectLatest { origin ->
            if (origin == DataOrigin.LIVE) job()
        }
    }

    override val activeSystemId: StateFlow<SystemId?> by lazy {
        // Starts the jobs: the active vehicle comes from the same HEARTBEATs as the map.
        vehicles
        activeVehicle.systemId
    }

    private suspend fun listenWhileObserved() {
        SharingStarted.WhileSubscribed(LINK_STOP_TIMEOUT)
            .command(mutableVehicles.subscriptionCount)
            .distinctUntilChanged()
            .collectLatest { command ->
                listening = command == SharingCommand.START
                if (listening) {
                    session.messages.collect { received ->
                        // Marked on arrival, not when the watchdog gets to it.
                        val at = timeSource.markNow()
                        if (telemetryStore.record(received, at)) {
                            events.send(Event.Rebooted(SystemId(received.systemId)))
                        }
                        HeartbeatMapper.vehicleStateOf(received)?.let { state ->
                            events.send(Event.Heard(state, at, received.sender, received.session))
                        }
                    }
                }
            }
    }

    private suspend fun watch() = coroutineScope {
        val timers = mutableMapOf<SystemId, Job>()
        // Where the store starts too: a session that began before the watchdog did still arrives as a change.
        var current = FIRST_SESSION

        fun startTimer(systemId: SystemId, after: Duration) {
            timers.remove(systemId)?.cancel()
            timers[systemId] = launch {
                delay(after.atLeastRecheck())
                events.send(Event.TimedOut(systemId))
            }
        }

        try {
            for (event in events) {
                when (event) {
                    // Still in flight from the session before: it belongs to nothing shown now.
                    is Event.Heard -> if (event.session == current) {
                        val systemId = event.state.systemId
                        val elapsed = event.at.elapsedNow()
                        // Normally CONNECTED; LOST only if the watchdog got to this HEARTBEAT after the
                        // timeout had already passed, so a late event never flashes CONNECTED.
                        val link = rule.linkAfter(elapsed)
                        addresses += systemId to event.sender
                        update(systemId, Vehicle(event.state, link, event.at, origin = event.session.origin))
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

                            else -> update(event.systemId, vehicle.copy(link = VehicleLink.LOST))
                        }
                    }

                    is Event.Rebooted -> requestAgainAfterReboot(event.systemId)

                    is Event.SessionChanged -> current = changeSession(from = current, to = event.session, timers)
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
            activeLinkChanges.trySend(null)
            activeLinkChanges.close()
        }
    }

    /**
     * Forgets every vehicle, timer, address and telemetry of the session [from], if [to] is another one, and returns the
     * session now. Any request target still queued is dropped, so none outlives its session, and the telemetry job is
     * told no vehicle is to be asked. Only the watchdog calls it, and only it sends targets.
     */
    private fun changeSession(from: SessionId, to: SessionId, timers: MutableMap<SystemId, Job>): SessionId {
        if (to == from) return from
        timers.values.forEach(Job::cancel)
        timers.clear()
        addresses = emptyMap()
        mutableVehicles.value = emptyMap()
        telemetryStore.startSession(to)
        do {
            val drained = activeLinkChanges.tryReceive().isSuccess
        } while (drained)
        activeLinkChanges.trySend(null)
        return to
    }

    /**
     * Writes [vehicle] and tells the telemetry job if the active vehicle's link changed. Only the watchdog
     * calls it, after writing the vehicle's address.
     */
    private fun update(systemId: SystemId, vehicle: Vehicle) {
        val before = mutableVehicles.value[systemId]?.link ?: VehicleLink.LOST
        mutableVehicles.value += systemId to vehicle
        // Only the live vehicle is ever asked for anything: a replayed one has no address to send to.
        if (systemId == activeVehicle.systemId.value && vehicle.origin == DataOrigin.LIVE && vehicle.link != before) {
            val target = if (vehicle.link == VehicleLink.CONNECTED) {
                TelemetryTarget(systemId, addresses.getValue(systemId))
            } else {
                null
            }
            activeLinkChanges.trySend(target)
        }
    }

    /**
     * Tells the telemetry job to start a new round if [systemId] is the active vehicle and CONNECTED; otherwise it
     * gets one when it is CONNECTED again. Only the watchdog calls it.
     */
    private fun requestAgainAfterReboot(systemId: SystemId) {
        val vehicle = mutableVehicles.value[systemId]
        val connected = vehicle?.link == VehicleLink.CONNECTED && vehicle.origin == DataOrigin.LIVE
        if (systemId == activeVehicle.systemId.value && connected) {
            activeLinkChanges.trySend(TelemetryTarget(systemId, addresses.getValue(systemId)))
        }
    }

    /**
     * The active vehicle's address while it is CONNECTED, otherwise `null`. CONNECTED is checked twice, as
     * the HUD does: the watchdog's verdict and the rule applied to the age right now, because the watchdog's
     * timer can run late after the device sleeps (#12) and this GCS must not keep announcing itself then.
     */
    private fun activeAddressWhileConnected(): InetSocketAddress? {
        val systemId = activeVehicle.systemId.value
        val vehicle = systemId?.let(mutableVehicles.value::get)
        val connected = vehicle != null &&
            vehicle.origin == DataOrigin.LIVE &&
            vehicle.link == VehicleLink.CONNECTED &&
            rule.linkAfter(vehicle.lastHeartbeat.elapsedNow()) == VehicleLink.CONNECTED
        return if (connected) addresses[systemId] else null
    }

    /**
     * Whether the active vehicle is heard right now: the link is listened to and the vehicle is CONNECTED by
     * the same double check as [activeAddressWhileConnected]. The telemetry job asks before requesting a
     * silent stream again, so it never opens the socket again for nobody nor speaks to a vehicle the device
     * slept away from.
     */
    private fun activeHeardNow(): Boolean = listening && activeAddressWhileConnected() != null

    private sealed interface Event {
        data class Heard(
            val state: VehicleState,
            val at: ComparableTimeMark,
            val sender: InetSocketAddress,
            val session: SessionId,
        ) : Event

        data class TimedOut(val systemId: SystemId) : Event

        data class Rebooted(val systemId: SystemId) : Event

        data class SessionChanged(val session: SessionId) : Event
    }
}
