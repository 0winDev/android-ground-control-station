package com.owindev.gcs.core.domain.preflight

/** Whether a [PreflightCheck] lets the vehicle arm (REQ-048): a red item outranks any amber. */
sealed interface PreflightVerdict {

    data object Clear : PreflightVerdict

    /** No red item, but these amber ones have not been acknowledged. */
    data class NeedsAcknowledgement(val ambers: List<PreflightItem>) : PreflightVerdict

    data class Blocked(val reds: List<PreflightItem>) : PreflightVerdict
}

/** An acknowledgement counts only for an amber item equal to the acknowledged one: same kind, colour and reason. */
fun PreflightCheck.verdict(acknowledged: PreflightAcknowledgements): PreflightVerdict {
    val unacknowledged = ambers.filterNot { it in acknowledged.items }
    return when {
        reds.isNotEmpty() -> PreflightVerdict.Blocked(reds)
        unacknowledged.isNotEmpty() -> PreflightVerdict.NeedsAcknowledgement(unacknowledged)
        else -> PreflightVerdict.Clear
    }
}

/**
 * The amber items the operator has acknowledged. Passed every new check through [keptFor], an acknowledgement ends
 * when its item changes or clears, so an item that comes back must be acknowledged again; a reading that changes
 * within the same reason keeps it, since amber reasons hold no live value.
 */
data class PreflightAcknowledgements(val items: Set<PreflightItem> = emptySet()) {

    /** Only an amber item of [check] can be acknowledged. */
    fun acknowledge(item: PreflightItem, check: PreflightCheck): PreflightAcknowledgements =
        if (item in check.ambers) copy(items = items + item) else this

    fun keptFor(check: PreflightCheck): PreflightAcknowledgements {
        val kept = items.filterTo(mutableSetOf()) { it in check.ambers }
        return if (kept == items) this else PreflightAcknowledgements(kept)
    }
}
