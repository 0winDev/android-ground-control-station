package com.owindev.gcs.data.vehicle.telemetry

import kotlin.math.PI
import kotlin.math.abs

/**
 * The documented range of each telemetry field [TelemetryMapper] maps (REQ-031), on the raw value as sent: a
 * value outside it is `null`, never clamped or wrapped, because the link is untrusted input and a HUD must not
 * show it as current. Ranges quoted from the field descriptions in `common.xml` and `standard.xml`
 * (https://mavlink.io/en/messages/common.html); fields with no range are taken as sent, floats only if finite.
 */
internal object TelemetryRanges {

    /**
     * GLOBAL_POSITION_INT `hdg` (`standard.xml`) and GPS_RAW_INT `cog` (`common.xml`): "0.0..359.99 degrees", in
     * cdeg. Their "unknown", UINT16_MAX, is past it too.
     */
    private const val DIRECTION_MAX_CDEG = 35_999

    /**
     * GLOBAL_POSITION_INT `lat` and `lon` are degE7 with no range in `standard.xml`: a latitude past ±90° or a
     * longitude past ±180° is no place on the WGS84 ellipsoid.
     */
    private const val LATITUDE_MAX_DEGE7 = 900_000_000
    private const val LONGITUDE_MAX_DEGE7 = 1_800_000_000

    /** ATTITUDE angles: "(-pi..+pi)", compared as floats, so the float closest to ±π, a real reading, is in range. */
    private const val ANGLE_MAX_RAD = PI.toFloat()

    /** VFR_HUD `throttle`: "Current throttle setting (0 to 100)". */
    private const val THROTTLE_MAX_PERCENT = 100

    /** Whether [lat] and [lon], in degE7, are a place that exists. */
    fun isOnEarth(lat: Int, lon: Int): Boolean =
        // A range, not abs(): abs(Int.MIN_VALUE) overflows to a negative number and would pass.
        lat in -LATITUDE_MAX_DEGE7..LATITUDE_MAX_DEGE7 && lon in -LONGITUDE_MAX_DEGE7..LONGITUDE_MAX_DEGE7

    /** A heading or course in cdeg, or `null` if unknown or past 359.99 degrees. */
    fun direction(cdeg: Int): Int? = if (cdeg in 0..DIRECTION_MAX_CDEG) cdeg else null

    /** An ATTITUDE angle in radians, or `null` if NaN, infinite or past ±π. */
    fun angle(radians: Float): Float? = if (radians.isFinite() && abs(radians) <= ANGLE_MAX_RAD) radians else null

    /** A float with no documented range, or `null` if NaN or infinite. */
    fun finite(value: Float): Float? = if (value.isFinite()) value else null

    /** A throttle in percent, or `null` past 100. */
    fun throttle(percent: Int): Int? = if (percent in 0..THROTTLE_MAX_PERCENT) percent else null
}
