package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.domain.battery.BatteryThresholdRepository
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import com.owindev.gcs.data.vehicle.link.LinkSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * It observes [VehicleRepository.vehicles] as well, because that is what keeps the link listened to, as the message
 * repository does. Only the active vehicle's values of the link session are ever shown: until the store has moved on to
 * a new session, the one before reads as nothing read (REQ-037).
 */
@Singleton
internal class DefaultBatteryThresholdRepository @Inject constructor(
    store: ParameterStore,
    vehicleRepository: VehicleRepository,
    session: LinkSession,
    @ApplicationScope scope: CoroutineScope,
) : BatteryThresholdRepository {

    override val activeThresholds: StateFlow<BatteryThresholds> = combine(
        vehicleRepository.vehicles,
        vehicleRepository.activeSystemId,
        store.parameters,
        session.session,
    ) { _, activeSystemId, followed, current ->
        val active = activeSystemId != null && followed.session == current && followed.systemId == activeSystemId
        if (active) batteryThresholdsOf(followed.values) else BatteryThresholds.UNKNOWN
    }.stateIn(scope = scope, started = WHILE_OBSERVED, initialValue = BatteryThresholds.UNKNOWN)

    private companion object {
        val WHILE_OBSERVED = SharingStarted.WhileSubscribed(
            stopTimeout = Duration.ZERO,
            replayExpiration = Duration.ZERO,
        )
    }
}
