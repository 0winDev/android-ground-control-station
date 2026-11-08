package com.owindev.gcs.core.domain.link

import kotlin.time.TimeMark

/**
 * Whether the vehicle may not be hearing this GCS (REQ-045): after [failedPeriodsToWarn] HEARTBEAT periods failed in a
 * row, or while a command is left without answer. "May": a vehicle that ignores a command reads the same.
 */
class UplinkRule(val failedPeriodsToWarn: Int = DEFAULT_FAILED_PERIODS_TO_WARN) {

    init {
        require(failedPeriodsToWarn > 0) { "The failed periods to warn must be positive, was $failedPeriodsToWarn" }
    }

    /** Failing sends win over a command without answer: they are what the operator can act on first. */
    fun warningOf(health: UplinkHealth): UplinkWarning? {
        val unansweredSince = health.unansweredSince
        return when {
            health.failedHeartbeatPeriods >= failedPeriodsToWarn -> UplinkWarning.SendsFailing
            unansweredSince != null -> UplinkWarning.CommandUnanswered(unansweredSince)
            else -> null
        }
    }

    companion object {
        /**
         * Three 1 Hz periods: past one or two lost to a transient error, and still within ArduCopter's default
         * `FS_GCS_TIMEOUT` of 5 s (`ArduCopter/Parameters.cpp`), so the operator hears of it before the failsafe acts.
         */
        const val DEFAULT_FAILED_PERIODS_TO_WARN: Int = 3
    }
}

/** Why the vehicle may not be hearing this GCS. */
sealed interface UplinkWarning {

    /** The device failed to send this GCS's HEARTBEAT in a row. */
    data object SendsFailing : UplinkWarning

    /** A command was left without answer at [since], and no answer from the vehicle came after it. */
    data class CommandUnanswered(val since: TimeMark) : UplinkWarning
}
