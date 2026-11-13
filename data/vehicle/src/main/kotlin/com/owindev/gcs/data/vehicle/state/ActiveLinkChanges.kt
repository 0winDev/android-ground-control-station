package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.data.vehicle.link.VehicleTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Each change of the active vehicle's link, handed to every job that asks it for something: its target when it becomes
 * CONNECTED or reboots while CONNECTED, `null` when it is lost. One channel per consumer, not a state: two changes in a
 * row must both arrive, and each has a single consumer, so nothing is replayed to a late one.
 *
 * Consumers are added while the owner is built, before the first change; only the watchdog announces.
 */
internal class ActiveLinkChanges {

    private val channels = mutableListOf<Channel<VehicleTarget?>>()

    fun newConsumer(): Flow<VehicleTarget?> {
        val channel = Channel<VehicleTarget?>(Channel.UNLIMITED)
        channels += channel
        return channel.receiveAsFlow()
    }

    fun announce(target: VehicleTarget?) {
        channels.forEach { channel -> channel.trySend(target) }
    }

    /** Drops every change still queued, so none outlives its session, and tells each consumer there is no vehicle. */
    fun restart() {
        channels.forEach { channel ->
            do {
                val drained = channel.tryReceive().isSuccess
            } while (drained)
        }
        announce(null)
    }

    /** Tells each consumer there is no vehicle, and that no change will follow. */
    fun close() {
        announce(null)
        channels.forEach { channel -> channel.close() }
    }
}
