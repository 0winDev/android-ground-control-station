package com.owindev.gcs.core.domain.vehicle

/** The last known state of one vehicle, as reported by its own HEARTBEAT. */
data class VehicleState(val systemId: SystemId, val type: VehicleType, val flightMode: FlightMode, val armed: Boolean)
