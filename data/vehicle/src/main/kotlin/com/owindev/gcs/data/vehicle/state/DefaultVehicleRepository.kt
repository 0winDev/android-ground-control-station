package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.LINK_STOP_TIMEOUT
import com.owindev.gcs.data.vehicle.link.LinkSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps the state of every vehicle heard on the shared [LinkSession], keyed by system ID (REQ-016).
 *
 * A HEARTBEAT only replaces the entry of the system that sent it. The map starts empty with every new
 * session; a late observer of a running session sees each vehicle with its next HEARTBEAT (1 Hz), never
 * with an old one.
 */
@Singleton
internal class DefaultVehicleRepository @Inject constructor(
    private val session: LinkSession,
    @param:ApplicationScope private val scope: CoroutineScope,
) : VehicleRepository {

    override val vehicles: StateFlow<Map<SystemId, VehicleState>> by lazy {
        session.messages
            .runningFold(emptyMap<SystemId, VehicleState>()) { vehicles, received ->
                val state = HeartbeatMapper.vehicleStateOf(received) ?: return@runningFold vehicles
                vehicles + (state.systemId to state)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(LINK_STOP_TIMEOUT),
                initialValue = emptyMap(),
            )
    }
}
