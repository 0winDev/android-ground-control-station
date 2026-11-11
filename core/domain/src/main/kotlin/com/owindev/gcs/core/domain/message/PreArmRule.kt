package com.owindev.gcs.core.domain.message

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which messages are a pre-arm reason, and for how long one counts as current (REQ-047). Informational only: whether
 * the vehicle's pre-arm checks pass is SYS_STATUS's `MAV_SYS_STATUS_PREARM_CHECK` (#102), never this text, which can be
 * lost or grow old while the failure lasts.
 */
class PreArmRule(val maxAge: Duration = DEFAULT_MAX_AGE) {

    init {
        require(maxAge > Duration.ZERO) { "A pre-arm reason's maximum age must be positive, was $maxAge" }
    }

    /** ArduPilot prefixes every failed pre-arm check with it (`AP_Arming::check_failed`, `AP_Arming.cpp`). */
    fun isPreArm(message: VehicleMessage): Boolean = message.text.startsWith(PREFIX)

    /** Whether [reason] is young enough to be read as the vehicle's current reason, not an old one. */
    fun isCurrent(reason: VehicleMessage): Boolean = reason.receivedAt.elapsedNow() < maxAge

    companion object {
        const val PREFIX = "PreArm: "

        /**
         * ArduPilot repeats a failing check every 30 s while disarmed (`PREARM_DISPLAY_PERIOD`, `AP_Arming.cpp`);
         * QGroundControl 4.4 drops its pre-arm error after 35 s (`_prearmErrorTimeoutMSecs`, `Vehicle.h`).
         */
        val DEFAULT_MAX_AGE: Duration = 35.seconds
    }
}
