package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.TimeMark

/**
 * A text the vehicle's autopilot sent (REQ-047), whole or joined from its chunks. [incomplete] when a chunk never
 * arrived: [text] then holds only the chunks received, in order. The text is untrusted input from the link.
 */
data class VehicleMessage(
    val severity: MessageSeverity,
    val text: String,
    val receivedAt: TimeMark,
    val origin: DataOrigin,
    val incomplete: Boolean = false,
)

/** `MAV_SEVERITY`, most severe first; [UNKNOWN] for a value outside its range, never taken for a real level. */
enum class MessageSeverity {
    EMERGENCY,
    ALERT,
    CRITICAL,
    ERROR,
    WARNING,
    NOTICE,
    INFO,
    DEBUG,
    UNKNOWN,
}
