package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.alert.AlertLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which vehicle message the flight screen shows at a glance (REQ-047). A newer message at the shown one's level or
 * above replaces it at once; a less urgent one only once the shown one is [hold] old, or [seriousHold] for an
 * emergency, so a burst of boot texts never pushes a serious one away before it can be read, even out of a kept
 * list full at [listCapacity]. Both are time limits counted from when a message was received, not shown: nothing is
 * held for ever, and a message that waited behind a hold may be replaced as soon as it shows.
 */
class ShownMessageRule(
    val hold: Duration = DEFAULT_HOLD,
    val seriousHold: Duration = DEFAULT_SERIOUS_HOLD,
    val listCapacity: Int = DEFAULT_LIST_CAPACITY,
) {

    init {
        require(hold > Duration.ZERO) { "A message's hold must be positive, was $hold" }
        require(seriousHold >= hold) { "A serious message's hold must not be shorter than $hold, was $seriousHold" }
        require(listCapacity > 0) { "The kept list's capacity must be positive, was $listCapacity" }
    }

    /**
     * The message to show next, given the one [shown] of the same vehicle and the kept [messages], newest first:
     * `null` when none is kept (a new session). A [shown] message a full list dropped stays until its hold ends; one
     * missing from a list that is not full belongs to another session or vehicle and gives way to the newest.
     */
    fun next(shown: VehicleMessage?, messages: List<VehicleMessage>): VehicleMessage? {
        if (shown == null || messages.isEmpty() || isOtherList(shown, messages)) return messages.firstOrNull()
        val newer = newerThan(shown, messages)
        return newer.firstOrNull { it.severity.level >= shown.severity.level }
            ?: newer.firstOrNull()?.takeIf { isHoldOver(shown) }
            ?: shown
    }

    /**
     * How long until [next] can change without a new message: the rest of [shown]'s hold while a less urgent message
     * waits behind it; `null` when nothing waits.
     */
    fun untilChange(shown: VehicleMessage?, messages: List<VehicleMessage>): Duration? {
        if (shown == null || newerThan(shown, messages).isEmpty() || next(shown, messages) != shown) return null
        return (holdOf(shown) - shown.receivedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** [shown] missing from a list that is not full: nothing was dropped, so it is another session's or vehicle's. */
    private fun isOtherList(shown: VehicleMessage, messages: List<VehicleMessage>): Boolean =
        shown !in messages && messages.size < listCapacity

    /** Every kept message is newer than one the list dropped, as the list drops its oldest first. */
    private fun newerThan(shown: VehicleMessage, messages: List<VehicleMessage>): List<VehicleMessage> {
        val index = messages.indexOf(shown)
        return if (index < 0) messages else messages.subList(0, index)
    }

    private fun isHoldOver(shown: VehicleMessage): Boolean = shown.receivedAt.elapsedNow() >= holdOf(shown)

    private fun holdOf(message: VehicleMessage): Duration =
        if (message.severity.level == AlertLevel.EMERGENCY) seriousHold else hold

    companion object {
        val DEFAULT_HOLD: Duration = 5.seconds
        val DEFAULT_SERIOUS_HOLD: Duration = 30.seconds

        /** REQ-047 keeps at most 100 messages. */
        const val DEFAULT_LIST_CAPACITY = 100
    }
}
