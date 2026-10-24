package com.owindev.gcs.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.attention.FlightAttention
import com.owindev.gcs.core.domain.attention.FlightAttentionRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Watches the live flight for the whole time the app is open, whatever screen is on (ADR 0007): whether the operator
 * may leave the flight screen, and when the app must take them back to it. It keeps the alerts observed while another
 * screen is on, so an emergency is raised there too. The decisions are [FlightAttentionRule]'s.
 */
@HiltViewModel
class FlightGuardViewModel @Inject constructor(
    vehicleRepository: VehicleRepository,
    alertRepository: AlertRepository,
    private val rule: FlightAttentionRule,
) : ViewModel() {

    private val attention: StateFlow<FlightAttention> =
        combine(vehicleRepository.vehicles, alertRepository.activeAlerts) { vehicles, alerts ->
            rule.attentionOf(vehicles.values, alerts)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CALM)

    private val returns = Channel<Unit>(Channel.CONFLATED)

    /** Whether the operator may open another screen now: never while a live vehicle is armed. */
    val mayLeaveFlight: StateFlow<Boolean> =
        attention.map(rule::mayLeave).stateIn(viewModelScope, SharingStarted.Eagerly, rule.mayLeave(CALM))

    /** Each time the app must go back to the flight screen: a live vehicle armed or a new live emergency. */
    val returnToFlight: Flow<Unit> = returns.receiveAsFlow()

    init {
        viewModelScope.launch {
            var before = attention.value
            attention.collect { now ->
                if (rule.returnsToFlight(before, now)) returns.trySend(Unit)
                before = now
            }
        }
    }

    private companion object {
        val CALM = FlightAttention(liveArmed = false, emergencies = emptySet())
    }
}
