package com.owindev.gcs.core.domain.vehicle

/**
 * The flight mode a vehicle reports. Mode numbers are autopilot-specific, so they are translated in
 * `:data:vehicle` (ADR 0003) and the domain only sees the result.
 */
sealed interface FlightMode {

    /** A mode the autopilot documents, with [name] as its documentation spells it (e.g. `AltHold`). */
    data class Named(val name: String) : FlightMode

    /** A mode [number] that has no documented name for this vehicle; shown as the raw number. */
    data class Unknown(val number: Long) : FlightMode
}
