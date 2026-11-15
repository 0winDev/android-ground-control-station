package com.owindev.gcs.core.domain.preflight

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark

/** Whether a [PreflightCheck] lets the vehicle arm (REQ-048): a red item outranks any amber. */
sealed interface PreflightVerdict {

    data object Clear : PreflightVerdict

    /** No red item, but these amber ones have no acknowledgement that still counts. */
    data class NeedsAcknowledgement(val ambers: List<PreflightItem>) : PreflightVerdict

    data class Blocked(val reds: List<PreflightItem>) : PreflightVerdict
}

/** An acknowledgement counts only as [PreflightAcknowledgements.covers] says, judged now. */
fun PreflightCheck.verdict(acknowledged: PreflightAcknowledgements): PreflightVerdict {
    val unacknowledged = ambers.filterNot { acknowledged.covers(it, this) }
    return when {
        reds.isNotEmpty() -> PreflightVerdict.Blocked(reds)
        unacknowledged.isNotEmpty() -> PreflightVerdict.NeedsAcknowledgement(unacknowledged)
        else -> PreflightVerdict.Clear
    }
}

/**
 * The amber items the operator acknowledged, each with when, on one [connection]. An acknowledgement ends on its own
 * when the connection changes (a LOST, a reboot seen as one, another session) and after [MAX_AGE], even if nobody
 * judged the check meanwhile; passed each new check through [keptFor], it also ends when its item changes or clears.
 * A reading that changes within the same reason keeps it, since amber reasons hold no live value.
 */
data class PreflightAcknowledgements(
    val connection: Long? = null,
    val acknowledged: Map<PreflightItem, TimeMark> = emptyMap(),
) {

    /** Only an amber item of [check] can be acknowledged; one on another connection starts afresh. */
    fun acknowledge(item: PreflightItem, check: PreflightCheck, at: TimeMark): PreflightAcknowledgements = when {
        item !in check.ambers -> this
        connection == check.connection -> copy(acknowledged = acknowledged + (item to at))
        else -> PreflightAcknowledgements(check.connection, mapOf(item to at))
    }

    /** The same kind, colour and reason, on [check]'s connection, acknowledged less than [MAX_AGE] ago. */
    fun covers(item: PreflightItem, check: PreflightCheck): Boolean =
        connection == check.connection && acknowledged[item]?.let { it.elapsedNow() < MAX_AGE } == true

    fun keptFor(check: PreflightCheck): PreflightAcknowledgements {
        val kept = acknowledged.filterKeys { it in check.ambers && covers(it, check) }
        return if (kept == acknowledged) this else PreflightAcknowledgements(connection, kept)
    }

    companion object {
        /** The checklist is read right before arming: an older acknowledgement no longer says the operator saw it. */
        val MAX_AGE: Duration = 2.minutes
    }
}
