package com.owindev.gcs.navigation

import androidx.lifecycle.ViewModel
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.attention.FlightAttention
import com.owindev.gcs.core.domain.attention.FlightAttentionRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull

/**
 * Watches the live flight while [returnToFlight] is collected, whatever screen is on (ADR 0007): whether the operator
 * may leave the flight screen, and when the app must take them back to it. It keeps the alerts observed while another
 * screen is on, so an emergency is raised there too. The decisions are [FlightAttentionRule]'s.
 *
 * The app collects it only while it is started, so a backgrounded app lets the live link go as the screens do, and the
 * vehicle's GCS failsafe follows the app. A new collection compares the flight with the last one seen, so what changed
 * while the app was in the background still takes the operator back.
 */
@HiltViewModel
class FlightGuardViewModel @Inject constructor(
    vehicleRepository: VehicleRepository,
    alertRepository: AlertRepository,
    private val rule: FlightAttentionRule,
) : ViewModel() {

    private val attention: Flow<FlightAttention> =
        combine(vehicleRepository.vehicles, alertRepository.activeAlerts) { vehicles, alerts ->
            rule.attentionOf(vehicles.values, alerts)
        }

    /** Across collections; only the one collector touches it. */
    private var lastSeen = CALM

    private val mutableMayLeaveFlight = MutableStateFlow(rule.mayLeave(CALM))

    /**
     * Whether the operator may open another screen now: never while a live vehicle is armed. Kept as last seen while
     * [returnToFlight] is not collected.
     */
    val mayLeaveFlight: StateFlow<Boolean> = mutableMayLeaveFlight.asStateFlow()

    /**
     * Each time the app must go back to the flight screen: a live vehicle armed or a new live emergency. One
     * collector.
     */
    val returnToFlight: Flow<Unit> = attention.mapNotNull { now ->
        val before = lastSeen
        lastSeen = now
        mutableMayLeaveFlight.value = rule.mayLeave(now)
        Unit.takeIf { rule.returnsToFlight(before, now) }
    }

    private companion object {
        val CALM = FlightAttention(liveArmed = false, emergencies = emptySet())
    }
}
