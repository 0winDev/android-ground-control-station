package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.SystemId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * The active vehicle (REQ-020): the first autopilot whose HEARTBEAT the link delivers, pinned for as long as the app
 * runs. The one source for the vehicle repository and the flight recorder, so they never follow different vehicles.
 */
@Singleton
internal class ActiveVehicle @Inject constructor() {

    private val pinned = MutableStateFlow<SystemId?>(null)

    val systemId: StateFlow<SystemId?> = pinned.asStateFlow()

    /** Pins [systemId] unless a vehicle already is, and returns the active one; only the receive loop calls it. */
    fun pinFirst(systemId: SystemId): SystemId = pinned.updateAndGet { it ?: systemId } ?: systemId
}
