package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Trail
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
 * The active vehicle's telemetry and trail, from the [TelemetryStore].
 *
 * It observes [VehicleRepository.vehicles] as well, because that is what keeps the link listened to: the
 * telemetry is filled by the same listener as the HEARTBEATs, so observing it keeps the link open.
 *
 * Its last values expire as soon as nobody observes them (`replayExpiration` zero, as in the HUD, #13), so a new
 * observer never starts from a remembered value; and every value carries the time it arrived, so one that
 * stopped arriving still reads stale. Only the link session's own telemetry and trail are ever shown: until the store
 * has moved on to a new session, the one before reads as nothing received yet (REQ-037).
 */
@Singleton
internal class DefaultTelemetryRepository @Inject constructor(
    store: TelemetryStore,
    vehicleRepository: VehicleRepository,
    session: LinkSession,
    @ApplicationScope scope: CoroutineScope,
) : TelemetryRepository {

    override val activeTelemetry: StateFlow<Telemetry?> = combine(
        vehicleRepository.vehicles,
        vehicleRepository.activeSystemId,
        store.telemetry,
        session.session,
    ) { _, activeSystemId, telemetry, current ->
        // Heard but nothing received from it yet: every part null, which is not the same as no vehicle.
        val kept = telemetry.takeIf { store.session == current }.orEmpty()
        activeSystemId?.let { kept[it] ?: Telemetry(origin = current.origin) }
    }
        .stateIn(scope = scope, started = WHILE_OBSERVED, initialValue = null)

    override val activeTrail: StateFlow<Trail?> = combine(
        vehicleRepository.vehicles,
        vehicleRepository.activeSystemId,
        store.trails,
        session.session,
    ) { _, activeSystemId, trails, current ->
        val kept = trails.takeIf { store.session == current }.orEmpty()
        activeSystemId?.let { kept[it] ?: Trail.EMPTY }
    }
        .stateIn(scope = scope, started = WHILE_OBSERVED, initialValue = null)

    private companion object {
        val WHILE_OBSERVED = SharingStarted.WhileSubscribed(
            stopTimeout = Duration.ZERO,
            replayExpiration = Duration.ZERO,
        )
    }
}
