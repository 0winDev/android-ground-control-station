package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.message.level
import com.owindev.gcs.core.domain.message.shownText
import kotlin.time.Duration

/**
 * The active vehicle's messages on the HUD (REQ-047): [latest] at a glance, only while the vehicle is heard, and every
 * one [kept], newest first, which can be read even while the link is lost.
 */
data class VehicleMessages(val latest: MessageLine? = null, val kept: List<MessageLine> = emptyList())

/**
 * One vehicle message as shown: [text] made safe to show, at the [level] of its severity, with the `MAV_SEVERITY` sent
 * as [unknownSeverity] when it is outside 0–7, its [age] rounded as every age on the HUD, [incomplete] when a chunk
 * never came, and [recorded] when it comes from a replay (REQ-037).
 */
data class MessageLine(
    val text: String,
    val level: AlertLevel,
    val unknownSeverity: Int?,
    val age: Duration,
    val incomplete: Boolean,
    val recorded: Boolean,
)

/**
 * The messages [kept], newest first, with the one [shown] at a glance: one of them, one the list dropped while its
 * hold lasts, or `null`.
 */
internal data class MessageView(val shown: VehicleMessage?, val kept: List<VehicleMessage>)

/** Every age read at once, so the line and its row in the list always agree (lesson #73). */
internal fun MessageView.toMessages(latestVisible: Boolean): VehicleMessages {
    val lines = kept.map { it.toLine() }
    val index = shown?.let(kept::indexOf) ?: -1
    val latest = when {
        !latestVisible || shown == null -> null
        index >= 0 -> lines[index]
        else -> shown.toLine()
    }
    return VehicleMessages(latest = latest, kept = lines)
}

private fun VehicleMessage.toLine() = MessageLine(
    text = shownText(text),
    level = severity.level,
    unknownSeverity = severityValue.takeIf { severity == MessageSeverity.UNKNOWN },
    age = shownAge(receivedAt.elapsedNow()),
    incomplete = incomplete,
    recorded = origin == DataOrigin.REPLAY,
)
