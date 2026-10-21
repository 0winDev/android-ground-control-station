package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.TrailProgress
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last telemetry of every vehicle heard, keyed by system ID (REQ-016, REQ-026): a vehicle's telemetry
 * only ever comes from messages with its own system ID, never merged with another's.
 *
 * Written by the vehicle repository's listener, one message at a time, which marks each message on arrival, so
 * it is filled for exactly as long as the link is listened to. Nothing is ever removed: a vehicle that stops
 * sending keeps its last values, which grow stale. Only its home is forgotten, when it reboots (REQ-033).
 *
 * The [trail] (REQ-035) follows the active vehicle's positions as they arrive, one at a time, so none is lost to a
 * consumer that observes late or seldom. Only that vehicle's, so a stream of made-up system IDs cannot grow it.
 *
 * It holds one link session at a time, live or a replay (REQ-037): [startSession] forgets everything, and a message
 * from another session is dropped. The listener and the vehicle repository's watchdog both call it, hence the lock.
 */
@Singleton
internal class TelemetryStore @Inject constructor(
    private val trailRule: TrailRule,
    private val activeVehicle: ActiveVehicle,
) {

    private val lock = Any()

    private val mutableTelemetry = MutableStateFlow<Map<SystemId, Telemetry>>(emptyMap())

    private val mutableTrail = MutableStateFlow(FollowedTrail(FIRST_SESSION, systemId = null, TrailProgress.EMPTY))

    /** The last `time_boot_ms` of each autopilot message that carries one, by system ID and message; under [lock]. */
    private val lastBootTimes = mutableMapOf<Pair<SystemId, KClass<out MavlinkMessage>>, Long>()

    /** The session kept; written under [lock]. */
    @Volatile
    var session: SessionId = FIRST_SESSION
        private set

    val telemetry: StateFlow<Map<SystemId, Telemetry>> = mutableTelemetry.asStateFlow()

    val trail: StateFlow<FollowedTrail> = mutableTrail.asStateFlow()

    /**
     * Keeps [received] if it is telemetry from a vehicle's autopilot, marked [receivedAt], and returns whether it
     * shows that the vehicle rebooted: its home is then forgotten, since a rebooted autopilot sets it again.
     */
    fun record(received: ReceivedMessage, receivedAt: TimeMark): Boolean =
        synchronized(lock) { recordLocked(received, receivedAt) }

    private fun recordLocked(received: ReceivedMessage, receivedAt: TimeMark): Boolean {
        // System ID 0 is broadcast, never a sender (https://mavlink.io/en/guide/routing.html).
        if (received.systemId == BROADCAST_SYSTEM_ID || received.session != session) return false
        val systemId = SystemId(received.systemId)
        val rebooted = isReboot(systemId, received)
        val known = mutableTelemetry.value[systemId]
        val kept = if (rebooted) known?.copy(home = null) else known
        if (rebooted) closeTrail(systemId)
        val updated = TelemetryMapper.update(kept ?: Telemetry(origin = session.origin), received, receivedAt) ?: kept
        if (updated != null && updated != known) {
            mutableTelemetry.value += systemId to updated
            // A new position part, not an equal one: a GLOBAL_POSITION_INT has just arrived.
            if (updated.position !== kept?.position) followPosition(systemId, kept?.position, updated)
        }
        return rebooted
    }

    // Under lock, as is every write of the trail, so its session and vehicle always match its points.
    private fun followPosition(systemId: SystemId, previous: Timed<GlobalPosition>?, telemetry: Telemetry) {
        val progress = followedProgress(systemId) ?: return
        val next = trailRule.after(progress, previous, telemetry)
        if (next !== progress) mutableTrail.value = FollowedTrail(session, systemId, next)
    }

    // A reboot's gap in positions can be shorter than their staleness threshold, which would not break the segment.
    private fun closeTrail(systemId: SystemId) {
        val progress = followedProgress(systemId) ?: return
        val next = trailRule.closed(progress)
        if (next !== progress) mutableTrail.value = FollowedTrail(session, systemId, next)
    }

    /** The trail [systemId] extends: `null` unless it is the active vehicle, a fresh one if it has just become so. */
    private fun followedProgress(systemId: SystemId): TrailProgress? {
        if (systemId != activeVehicle.systemId.value) return null
        val followed = mutableTrail.value
        return if (followed.systemId == systemId) followed.progress else TrailProgress.EMPTY
    }

    /** Forgets every vehicle's telemetry and the trail and keeps only [session]'s from now on. */
    fun startSession(session: SessionId) = synchronized(lock) {
        this.session = session
        lastBootTimes.clear()
        mutableTelemetry.value = emptyMap()
        mutableTrail.value = FollowedTrail(session, systemId = null, TrailProgress.EMPTY)
    }

    /**
     * Whether [received]'s `time_boot_ms` went back more than [REBOOT_BACKSTEP] since the same message from the same
     * autopilot: each message is compared only with itself, since each is stamped when it is sent. A reboot forgets
     * the system's other times, so the next message to go back is the same reboot, not a second one.
     */
    private fun isReboot(systemId: SystemId, received: ReceivedMessage): Boolean {
        val bootTime = bootTimeOf(received.message)
        if (received.componentId != MAV_COMP_ID_AUTOPILOT1 || bootTime == null) return false
        val key = systemId to received.message::class
        val last = lastBootTimes.put(key, bootTime)
        val rebooted = last != null && last - bootTime > REBOOT_BACKSTEP.inWholeMilliseconds
        if (rebooted) lastBootTimes.keys.removeAll { it.first == systemId && it != key }
        return rebooted
    }

    private fun bootTimeOf(message: MavlinkMessage): Long? = when (message) {
        is MavlinkMessage.Attitude -> message.timeBootMs
        is MavlinkMessage.GlobalPositionInt -> message.timeBootMs
        else -> null
    }

    companion object {
        /**
         * How far `time_boot_ms` must go back to be a reboot rather than a datagram that arrived out of order, a few
         * stream periods old at most. In SITL (#69, ArduCopter 4.7.2) the first message after a reboot reads 2.4 s and
         * home is set about 20 s after boot, so the only reboot missed is one within about 5 s of boot, before home.
         * The uint32 wrapping after 49.7 days reads as a reboot, which only asks for home again.
         */
        val REBOOT_BACKSTEP: Duration = 3.seconds

        private const val BROADCAST_SYSTEM_ID = 0
        private const val MAV_COMP_ID_AUTOPILOT1 = 1
    }
}

/**
 * The trail of [systemId], the active vehicle when its positions arrived, in [session]; `null` while none has been
 * followed. Published whole, so a consumer never pairs one session's points with another's.
 */
internal data class FollowedTrail(val session: SessionId, val systemId: SystemId?, val progress: TrailProgress)
