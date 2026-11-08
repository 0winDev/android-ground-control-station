package com.owindev.gcs.link

import androidx.lifecycle.ViewModel
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

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
        !status.running && rule.mustRun(all.values, systemId?.let(all::get), disconnected)
    }
        .distinctUntilChanged()
        .filter { it }
        .map { }

    /** Once the notification is allowed while the service runs: posted at once, not at the vehicle's next change. */
    val postAgain: Flow<Unit> = state.status
        .runningFold(null to null) { (_, last): Pair<BackgroundLinkStatus?, BackgroundLinkStatus?>, now -> last to now }
        .filter { (before, now) -> before.hiddenWhileRunning && now?.notificationHidden == false }
        .map { }
}

private val BackgroundLinkStatus?.hiddenWhileRunning: Boolean
    get() = this != null && running && notificationHidden
