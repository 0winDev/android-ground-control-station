package com.owindev.gcs.core.domain.vehicle.command

/**
 * The altitudes above home a take off may ask for (REQ-044), [defaultMetres] unless the operator picks another. Anything
 * outside [minMetres]..[maxMetres], or not a number, is refused before anything is sent.
 */
class TakeOffAltitudeRule(
    val minMetres: Float = DEFAULT_MIN_METRES,
    val maxMetres: Float = DEFAULT_MAX_METRES,
    val defaultMetres: Float = DEFAULT_METRES,
) {

    init {
        require(minMetres > 0f && minMetres <= maxMetres) { "Invalid take off range $minMetres..$maxMetres m" }
        require(isValid(defaultMetres)) { "The default take off altitude $defaultMetres m is outside the range" }
    }

    fun isValid(metres: Float): Boolean = metres in minMetres..maxMetres

    companion object {
        /** REQ-044's range and default. */
        const val DEFAULT_MIN_METRES = 2f
        const val DEFAULT_MAX_METRES = 30f
        const val DEFAULT_METRES = 10f
    }
}
