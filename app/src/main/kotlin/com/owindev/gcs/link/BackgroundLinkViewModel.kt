package com.owindev.gcs.link

import androidx.lifecycle.ViewModel
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

/**
 * When to start [VehicleLinkService] (REQ-046): whenever the rule says it must run and it is not running, by level, so
 * arming after a Disconnect or a recreated activity starts it again. Collected only while the activity is started, so
 * it is always started from the foreground; once running, the service, not the activity, observes the link (#74).
 */
@HiltViewModel
class BackgroundLinkViewModel @Inject constructor(
    vehicleRepository: VehicleRepository,
    rule: BackgroundLinkRule,
    state: BackgroundLinkState,
) : ViewModel() {

    val starts: Flow<Unit> = combine(
        vehicleRepository.activeSystemId,
        vehicleRepository.vehicles,
        state.status,
        state.disconnectedByUser,
    ) { systemId, all, status, disconnected ->
        rule.mustRun(all.values, systemId?.let(all::get), disconnected) to status.running
    }
        .distinctUntilChanged()
        .withIndex()
        // Also when it already runs, on each start of the activity: a restricted app's service loses the foreground
        // once hidden, and only a new start puts it back.
        .filter { (index, value) -> value.first && (index == 0 || !value.second) }
        .map { }

    /**
     * When to ask `POST_NOTIFICATIONS`: once the service runs and no live vehicle is armed, so the system's dialog never
     * covers the HUD over an armed vehicle; a vehicle armed at the start defers it to its next disarmed moment.
     */
    val asksNotifications: Flow<Unit> = combine(vehicleRepository.vehicles, state.status) { all, status ->
        status.running && rule.operatorMayStop(all.values)
    }
        .distinctUntilChanged()
        .filter { it }
        .map { }
}
