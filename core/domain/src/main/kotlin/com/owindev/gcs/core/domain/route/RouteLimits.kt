package com.owindev.gcs.core.domain.route

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The limits a [Route] is judged by (REQ-055). */
object RouteLimits {

    /** REQ-044's lowest take off: no item may ask for less. */
    const val MIN_ALTITUDE_METRES = 2.0

    /**
     * EASA's open category, Regulation (EU) 2019/947, UAS.OPEN.010(2): "within 120 metres from the closest point of
     * the surface of the earth" (Easy Access Rules for UAS, June 2026). Here above home, not above the ground below.
     */
    const val MAX_ALTITUDE_METRES = 120.0

    /**
     * ArduCopter's smallest mission storage area, 2422 bytes (`StorageManager.cpp`), holds (2422 − 4) / 15 = 161
     * commands (`AP_Mission.cpp`), home included. SITL's 9758 bytes hold 650: a count of 651 was refused as
     * `MAV_MISSION_NO_SPACE` and 650 accepted (#150, ArduCopter 4.8.0-dev).
     */
    const val MAX_ITEMS = 160

    /**
     * The largest geofence circle around home, and its radius when a route sets none: the operator may only narrow it.
     * The open category flies in visual line of sight (Regulation (EU) 2019/947, Article 4(1)(d) and
     * UAS.OPEN.060(2)(b)), and 500 m is a practical range for that, not a figure of the regulation.
     */
    const val MAX_GEOFENCE_RADIUS_METRES = 500.0
    const val DEFAULT_GEOFENCE_RADIUS_METRES = MAX_GEOFENCE_RADIUS_METRES

    /**
     * ArduCopter refuses a NAV_WAYPOINT whose hold (`param1`) is negative or above UINT16_MAX seconds with
     * `MAV_MISSION_INVALID_PARAM1`, and keeps it in whole seconds (`AP_Mission.cpp`, git `caa5f0d2`).
     */
    const val MAX_HOLD_SECONDS = 65_535.0

    /**
     * Three intervals of the 1 Hz HOME_POSITION is requested at, the link-loss timeout: 0.98–1.03 s apart in SITL
     * once requested, none before (#150, ArduCopter 4.8.0-dev).
     */
    val MAX_HOME_AGE: Duration = 3.seconds
}
