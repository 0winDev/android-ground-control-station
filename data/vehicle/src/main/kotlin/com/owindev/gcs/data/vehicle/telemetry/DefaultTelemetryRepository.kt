package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.data.vehicle.di.ApplicationScope
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
 * The active vehicle's telemetry, from the [TelemetryStore].
 *
 * It observes [VehicleRepository.vehicles] as well, because that is what keeps the link listened to: the
 * telemetry is filled by the same listener as the HEARTBEATs, so observing it keeps the link open.
 *
 * Its last value expires as soon as nobody observes it (`replayExpiration` zero, as in the HUD, #13), so a new
 * observer never starts from a remembered value; and every value carries the time it arrived, so one that
 * stopped arriving still reads stale.
 */
@Singleton
internal class DefaultTelemetryRepository @Inject constructor(
    store: TelemetryStore,
    vehicleRepository: VehicleRepository,
    @ApplicationScope scope: CoroutineScope,
) : TelemetryRepository {

    override val activeTelemetry: StateFlow<Telemetry?> = combine(
        vehicleRepository.vehicles,
        vehicleRepository.activeSystemId,
        store.telemetry,
    ) { _, activeSystemId, telemetry ->
        // Heard but nothing received from it yet: every part null, which is not the same as no vehicle.
        activeSystemId?.let { telemetry[it] ?: Telemetry() }
    }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeout = Duration.ZERO, replayExpiration = Duration.ZERO),
            initialValue = null,
        )
}
