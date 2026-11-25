package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.alert.AlertLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which vehicle message the flight screen shows at a glance (REQ-047). A newer message at the shown one's level or
 * above replaces it at once; a less urgent one only once the shown one is [hold] old, or [seriousHold] for an
 * emergency, so a burst of boot texts never pushes a serious one away before it can be read. Both are time limits:
 * nothing is held for ever.
 */
class ShownMessageRule(val hold: Duration = DEFAULT_HOLD, val seriousHold: Duration = DEFAULT_SERIOUS_HOLD) {

    init {
        require(hold > Duration.ZERO) { "A message's hold must be positive, was $hold" }
        require(seriousHold >= hold) { "A serious message's hold must not be shorter than $hold, was $seriousHold" }
    }

    /**
     * The message to show next, given the one [shown] and the kept [messages], newest first: `null` only when none is
     * kept. A [shown] message no longer kept (a new session, another vehicle) gives way to the newest.
     */
    fun next(shown: VehicleMessage?, messages: List<VehicleMessage>): VehicleMessage? {
        val index = if (shown == null) -1 else messages.indexOf(shown)
        if (shown == null || index < 0) return messages.firstOrNull()
        val newer = messages.subList(0, index)
        return newer.firstOrNull { it.severity.level >= shown.severity.level }
            ?: newer.firstOrNull()?.takeIf { isHoldOver(shown) }
            ?: shown
    }

    /**
     * How long until [next] can change without a new message: the rest of [shown]'s hold while a less urgent message
     * waits behind it; `null` when nothing waits.
     */
    fun untilChange(shown: VehicleMessage?, messages: List<VehicleMessage>): Duration? {
        if (shown == null || messages.indexOf(shown) <= 0 || next(shown, messages) != shown) return null
        return (holdOf(shown) - shown.receivedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    private fun isHoldOver(shown: VehicleMessage): Boolean = shown.receivedAt.elapsedNow() >= holdOf(shown)

    private fun holdOf(message: VehicleMessage): Duration =
        if (message.severity.level == AlertLevel.EMERGENCY) seriousHold else hold

    companion object {
        val DEFAULT_HOLD: Duration = 5.seconds
        val DEFAULT_SERIOUS_HOLD: Duration = 30.seconds
    }
}
