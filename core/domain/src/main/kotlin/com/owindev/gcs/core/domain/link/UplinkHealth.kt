package com.owindev.gcs.core.domain.link

import kotlin.time.TimeMark

/**
 * What this GCS can observe of its uplink to the active vehicle (REQ-045): its HEARTBEAT periods failed in a row and
 * the last command left without answer. UDP gives no delivery proof, so a send that left the device is never evidence
 * that the vehicle heard it: only a COMMAND_ACK is.
 */
data class UplinkHealth(val failedHeartbeatPeriods: Int = 0, val unansweredSince: TimeMark? = null) {

    /** A sent HEARTBEAT ends a run of failed periods but never a command without answer. */
    fun afterHeartbeatSent(): UplinkHealth = copy(failedHeartbeatPeriods = 0)

    fun afterHeartbeatFailed(): UplinkHealth = copy(failedHeartbeatPeriods = failedHeartbeatPeriods + 1)

    fun afterCommandUnanswered(at: TimeMark): UplinkHealth = copy(unansweredSince = at)

    /** Only an answer from the vehicle's pinned route and system ID may be passed here. */
    fun afterCommandAnswered(): UplinkHealth = copy(unansweredSince = null)

    companion object {
        /** Nothing observed against the uplink: the state at the start of a session and after the vehicle is LOST. */
        val HEALTHY: UplinkHealth = UplinkHealth()
    }
}
