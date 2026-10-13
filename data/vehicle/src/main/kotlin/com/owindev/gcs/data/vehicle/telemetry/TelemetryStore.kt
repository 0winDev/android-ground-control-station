package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last telemetry of every vehicle heard, keyed by system ID (REQ-016, REQ-026): a vehicle's telemetry
 * only ever comes from messages with its own system ID, never merged with another's.
 *
 * Written by the vehicle repository's listener, which marks each message on arrival, so it is filled for
 * exactly as long as the link is listened to. Nothing is ever removed: a vehicle that stops sending keeps its
 * last values, which grow stale.
 */
@Singleton
internal class TelemetryStore @Inject constructor() {

    private val mutableTelemetry = MutableStateFlow<Map<SystemId, Telemetry>>(emptyMap())

    val telemetry: StateFlow<Map<SystemId, Telemetry>> = mutableTelemetry.asStateFlow()

    /** Keeps [received] if it is telemetry from a vehicle's autopilot, marked [receivedAt]. */
    fun record(received: ReceivedMessage, receivedAt: TimeMark) {
        // System ID 0 is broadcast, never a sender (https://mavlink.io/en/guide/routing.html).
        if (received.systemId == BROADCAST_SYSTEM_ID) return
        val systemId = SystemId(received.systemId)
        mutableTelemetry.update { all ->
            val updated = TelemetryMapper.update(all[systemId] ?: Telemetry(), received, receivedAt)
            if (updated == null) all else all + (systemId to updated)
        }
    }

    private companion object {
        const val BROADCAST_SYSTEM_ID = 0
    }
}
