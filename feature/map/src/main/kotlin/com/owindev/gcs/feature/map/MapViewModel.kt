package com.owindev.gcs.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.time.atLeastRecheck
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Maps the active vehicle, its telemetry and its trail to what the map shows (REQ-034).
 *
 * The vehicle is drawn as live only while its link is not lost ([LinkLossRule.isLost], as the HUD judges it), its
 * position is fresh and verified ([StalenessRule], REQ-026, REQ-029); otherwise it is greyed with the reason, and with
 * the position's age when it has one. The state is built again when something arrives, when a value goes stale, at
 * the link timeout and on every whole second of a shown age, so a position never stays live past its edge.
 *
 * The camera follows the vehicle until the operator moves the map ([onMapPanned]) and again after [onRecenter].
 * Nothing remembered is ever shown: with nobody observing for [STOP_TIMEOUT], the state goes back to empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    vehicleRepository: VehicleRepository,
    telemetryRepository: TelemetryRepository,
    private val linkLossRule: LinkLossRule,
    private val staleness: StalenessRule,
) : ViewModel() {

    private val following = MutableStateFlow(true)

    private val judged = combine(
        vehicleRepository.activeSystemId,
        vehicleRepository.vehicles,
        telemetryRepository.activeTelemetry,
        telemetryRepository.activeTrail,
    ) { activeSystemId, vehicles, telemetry, trail ->
        val vehicle = activeSystemId?.let(vehicles::get)
        flow {
            while (true) {
                emit(mapStateOf(vehicle, telemetry, trail, linkLossRule, staleness))
                val wait = untilMapChanges(vehicle, telemetry, linkLossRule, staleness)
                delay(wait?.atLeastRecheck() ?: awaitCancellation())
            }
        }
    }
        .flatMapLatest { it }

    val state: StateFlow<MapUiState> = combine(judged, following) { state, following ->
        state.copy(following = following)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeout = STOP_TIMEOUT, replayExpiration = Duration.ZERO),
            initialValue = MapUiState(),
        )

    /** The operator moved the map: the camera stays where they left it. */
    fun onMapPanned() {
        following.value = false
    }

    /** The camera goes back to the vehicle and follows it again. */
    fun onRecenter() {
        following.value = true
    }

    private companion object {
        /** Survives a configuration change without closing the link, as the HUD's. */
        val STOP_TIMEOUT = 5.seconds
    }
}
