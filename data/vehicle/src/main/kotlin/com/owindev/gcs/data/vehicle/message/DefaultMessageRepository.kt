package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.message.MessageRepository
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.LinkSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * It observes [VehicleRepository.vehicles] as well, because that is what keeps the link listened to, as the telemetry
 * repository does. Only the active vehicle's messages of the link session are ever shown: until the store has moved on
 * to a new session, the one before reads as nothing received (REQ-037).
 */
@Singleton
internal class DefaultMessageRepository @Inject constructor(
    store: MessageStore,
    vehicleRepository: VehicleRepository,
    session: LinkSession,
    @ApplicationScope scope: CoroutineScope,
) : MessageRepository {

    private val active: Flow<FollowedMessages?> = combine(
        vehicleRepository.vehicles,
        vehicleRepository.activeSystemId,
        store.messages,
        session.session,
    ) { _, activeSystemId, followed, current ->
        followed.takeIf { activeSystemId != null && it.session == current && it.systemId == activeSystemId }
    }

    override val activeMessages: StateFlow<List<VehicleMessage>> = active.map { it?.messages.orEmpty() }
        .stateIn(scope = scope, started = WHILE_OBSERVED, initialValue = emptyList())

    override val preArmReason: StateFlow<VehicleMessage?> = active.map { it?.preArmReason }
        .stateIn(scope = scope, started = WHILE_OBSERVED, initialValue = null)

    private companion object {
        val WHILE_OBSERVED = SharingStarted.WhileSubscribed(
            stopTimeout = Duration.ZERO,
            replayExpiration = Duration.ZERO,
        )
    }
}
