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
 * When to start [VehicleLinkService] (REQ-046): each time the active live vehicle becomes connected. Collected only
 * while the activity is started, so the service is always started from the foreground; once running, the service, not
 * the activity, keeps the link observed (#74).
 */
@HiltViewModel
class BackgroundLinkViewModel @Inject constructor(vehicleRepository: VehicleRepository, rule: BackgroundLinkRule) :
    ViewModel() {

    val starts: Flow<Unit> = combine(vehicleRepository.activeSystemId, vehicleRepository.vehicles) { systemId, all ->
        rule.startsFor(systemId?.let(all::get))
    }
        .distinctUntilChanged()
        .filter { it }
        .map { }
}
