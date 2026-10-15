package com.owindev.gcs.core.domain.telemetry

/** The kind of fix a GPS receiver has, as `GPS_FIX_TYPE` defines them (https://mavlink.io/en/messages/common.html#GPS_FIX_TYPE). */
sealed interface GpsFixType {

    /** No GPS connected. */
    data object NoGps : GpsFixType

    /** A GPS is connected but has no position. */
    data object NoFix : GpsFixType

    data object Fix2d : GpsFixType

    data object Fix3d : GpsFixType

    /** A 3D position aided by DGPS or SBAS. */
    data object Dgps : GpsFixType

    data object RtkFloat : GpsFixType

    data object RtkFixed : GpsFixType

    /** Static fixed, typically used for base stations. */
    data object Static : GpsFixType

    /** Precise point positioning, 3D. */
    data object Ppp : GpsFixType

    /** A fix type [number] that `GPS_FIX_TYPE` does not define. */
    data class Unknown(val number: Int) : GpsFixType
}

/** Whether this fix gives a 3D position; anything else, an undefined type included, is no 3D fix. */
val GpsFixType.isFix3d: Boolean
    get() = this in FIX_3D

private val FIX_3D: Set<GpsFixType> = setOf(
    GpsFixType.Fix3d,
    GpsFixType.Dgps,
    GpsFixType.RtkFloat,
    GpsFixType.RtkFixed,
    GpsFixType.Static,
    GpsFixType.Ppp,
)
