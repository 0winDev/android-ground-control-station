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
        state.running,
        state.disconnectedByUser,
    ) { systemId, all, running, disconnected ->
        !running && rule.mustRun(all.values, systemId?.let(all::get), disconnected)
    }
        .distinctUntilChanged()
        .filter { it }
        .map { }
}
