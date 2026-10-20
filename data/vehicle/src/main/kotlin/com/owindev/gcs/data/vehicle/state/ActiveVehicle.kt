package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.SystemId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The active vehicle (REQ-020): the first autopilot whose HEARTBEAT the link delivers. The one source for the vehicle
 * repository and the flight recorder, so they never follow different vehicles.
 *
 * The live link and a replay each pin their own (REQ-037). The live pin lasts for as long as the app runs; a replay's
 * lasts for that replay only, so when it ends the live link has its own vehicle back, as if no replay had happened.
 * [systemId] is the pin of whichever is playing now.
 */
@Singleton
internal class ActiveVehicle @Inject constructor() {

    private val lock = Any()

    // Guarded by lock.
    private var live: SystemId? = null
    private var replay: SystemId? = null
    private var origin = DataOrigin.LIVE

    private val current = MutableStateFlow<SystemId?>(null)

    val systemId: StateFlow<SystemId?> = current.asStateFlow()

    /** The live link's pin, whatever is playing now: the recorder only ever records the live link. */
    val liveSystemId: SystemId?
        get() = synchronized(lock) { live }

    /**
     * Pins [systemId] for [origin] unless a vehicle already is, and returns that origin's active one; only the receive
     * loop calls it.
     */
    fun pinFirst(systemId: SystemId, origin: DataOrigin): SystemId = synchronized(lock) {
        val pinned = when (origin) {
            DataOrigin.LIVE -> (live ?: systemId).also { live = it }
            DataOrigin.REPLAY -> (replay ?: systemId).also { replay = it }
        }
        publish()
        pinned
    }

    /** A replay starts with no vehicle pinned. */
    fun startReplay() = synchronized(lock) {
        replay = null
        origin = DataOrigin.REPLAY
        publish()
    }

    /** The replay's pin is forgotten and the live one is active again. */
    fun endReplay() = synchronized(lock) {
        replay = null
        origin = DataOrigin.LIVE
        publish()
    }

    // Under lock, so the published pin always matches the origin.
    private fun publish() {
        current.value = if (origin == DataOrigin.LIVE) live else replay
    }
}
