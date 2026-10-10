package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode

/**
 * ArduCopter's flight modes, as HEARTBEAT `custom_mode` numbers. The only place that knows them (ADR 0003).
 *
 * Source: the values of the `FLTMODE1` parameter in the ArduCopter documentation,
 * https://ardupilot.org/copter/docs/parameters.html#fltmode1-flight-mode-1 (checked 2026-10-10; the SITL
 * captures in the tests come from ArduCopter V4.7.2-beta1). Names are kept exactly as documented. Numbers
 * missing from the documentation (8, 10, 12, above 28) are not guessed: they map to [FlightMode.Unknown].
 */
internal object ArduCopterFlightModes {

    private val names: Map<Long, String> = mapOf(
        0L to "Stabilize",
        1L to "Acro",
        2L to "AltHold",
        3L to "Auto",
        4L to "Guided",
        5L to "Loiter",
        6L to "RTL",
        7L to "Circle",
        9L to "Land",
        11L to "Drift",
        13L to "Sport",
        14L to "Flip",
        15L to "AutoTune",
        16L to "PosHold",
        17L to "Brake",
        18L to "Throw",
        19L to "Avoid_ADSB",
        20L to "Guided_NoGPS",
        21L to "Smart_RTL",
        22L to "FlowHold",
        23L to "Follow",
        24L to "ZigZag",
        25L to "SystemID",
        26L to "Heli_Autorotate",
        27L to "Auto RTL",
        28L to "Turtle",
    )

    fun flightModeOf(customMode: Long): FlightMode =
        names[customMode]?.let(FlightMode::Named) ?: FlightMode.Unknown(customMode)
}
