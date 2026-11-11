package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.StatusText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/**
 * Joins one vehicle's STATUSTEXT chunks into [VehicleMessage]s of [origin] (REQ-047). `id` 0 is a whole message;
 * chunks of another `id` are kept by `chunk_seq`, in any order, until the one whose text ends in its field and every
 * chunk before it have arrived. One message is pending at a time, as ArduPilot queues a message's chunks one after
 * the other (`GCS::send_textv`, `GCS_Common.cpp`): a chunk of another `id`, or [timeLimit] without a chunk, closes it
 * with the chunks received, marked incomplete. Not thread-safe: its owner serialises the calls.
 */
internal class StatusTextAssembler(
    private val origin: DataOrigin,
    private val timeLimit: Duration = CHUNK_TIME_LIMIT,
) {

    private var pending: Pending? = null

    /** The messages [statusText], received [at], completes: the pending one it closes, then its own. */
    fun accept(statusText: StatusText, at: TimeMark): List<VehicleMessage> {
        val closed = pending?.takeIf { it.id != statusText.id }?.let { closeIncomplete() }
        val own = if (statusText.id == WHOLE_MESSAGE_ID) {
            message(statusText.severity, statusText.text, at, incomplete = false)
        } else {
            chunk(statusText, at)
        }
        return listOfNotNull(closed, own)
    }

    /** The pending message, incomplete, once [timeLimit] has passed since its last chunk; `null` otherwise. */
    fun expired(): VehicleMessage? = pending?.takeIf { it.lastChunkAt.elapsedNow() >= timeLimit }?.let {
        closeIncomplete()
    }

    private fun chunk(statusText: StatusText, at: TimeMark): VehicleMessage? {
        if (statusText.chunkSeq >= MAX_CHUNKS) return null
        val chunks = pending ?: Pending(statusText.id, statusText.severity, firstAt = at).also { pending = it }
        return if (chunks.keep(statusText, at) && chunks.isComplete()) {
            pending = null
            chunks.joined(incomplete = false)
        } else {
            null
        }
    }

    private fun closeIncomplete(): VehicleMessage? = pending?.joined(incomplete = true).also { pending = null }

    private fun Pending.joined(incomplete: Boolean): VehicleMessage =
        message(severity, texts.toSortedMap().values.joinToString(separator = ""), firstAt, incomplete)

    private fun message(severity: Int, text: String, at: TimeMark, incomplete: Boolean) = VehicleMessage(
        severity = severityOf(severity),
        text = text,
        receivedAt = at,
        origin = origin,
        incomplete = incomplete,
    )

    /** `MAV_SEVERITY` 0 (emergency) to 7 (debug) are in [MessageSeverity]'s order; anything else is unknown. */
    private fun severityOf(value: Int): MessageSeverity = if (value in MAV_SEVERITY_EMERGENCY..MAV_SEVERITY_DEBUG) {
        MessageSeverity.entries[value]
    } else {
        MessageSeverity.UNKNOWN
    }

    /** The chunks of message [id] received so far, by `chunk_seq`; [lastSeq] once the last one has arrived. */
    private class Pending(val id: Int, val severity: Int, val firstAt: TimeMark) {
        val texts = mutableMapOf<Int, String>()
        var lastChunkAt: TimeMark = firstAt
        var lastSeq: Int? = null

        /** Keeps [chunk], received [at], unless it is a copy or comes after the last chunk; returns whether it did. */
        fun keep(chunk: StatusText, at: TimeMark): Boolean {
            val seq = chunk.chunkSeq
            val beyondLast = lastSeq?.let { seq > it } ?: false
            if (beyondLast || seq in texts) return false
            texts[seq] = chunk.text
            lastChunkAt = at
            if (chunk.endsInField) lastSeq = seq
            return true
        }

        fun isComplete(): Boolean = lastSeq?.let { last -> (0..last).all { it in texts } } ?: false
    }

    companion object {
        /** QGroundControl 4.4 closes a chunked text 1 s after its last chunk (`_chunkedStatusTextTimer`, `Vehicle.cc`). */
        val CHUNK_TIME_LIMIT: Duration = 1.seconds

        /**
         * ArduPilot formats a text into 256 characters (`statustext_printf_buffer`, `GCS.h`), at most 6 chunks of 50
         * bytes; a later `chunk_seq` is not ArduPilot's and is dropped, so a made-up one cannot grow the buffer.
         */
        const val MAX_CHUNKS = 6

        private const val WHOLE_MESSAGE_ID = 0
        private const val MAV_SEVERITY_EMERGENCY = 0
        private const val MAV_SEVERITY_DEBUG = 7
    }
}
