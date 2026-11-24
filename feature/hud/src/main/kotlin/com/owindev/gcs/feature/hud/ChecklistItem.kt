package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightCheck
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.preflight.PreflightVerdict
import com.owindev.gcs.core.domain.preflight.verdict
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import kotlin.time.Duration

/**
 * One item of the pre-flight checklist shown before arming (REQ-048): an amber one [acknowledged] while its
 * acknowledgement still counts, and the vehicle's pre-arm reason with how long ago it came ([reasonAge]).
 */
data class ChecklistItem(val item: PreflightItem, val acknowledged: Boolean = false, val reasonAge: Duration? = null)

/** [check]'s items as the checklist shows them: red first, then amber, then green, each group in the check's order. */
internal fun PreflightCheck.toChecklist(acknowledged: PreflightAcknowledgements): List<ChecklistItem> =
    items.sortedBy { it.colour.rank() }.map { item ->
        ChecklistItem(
            item = item,
            acknowledged = item.colour == PreflightColour.AMBER && acknowledged.covers(item, this),
            reasonAge = (item.reason as? PreflightReason.PreArmFailing)?.receivedAt?.let { shownAge(it.elapsedNow()) },
        )
    }

/** Why the check, with [acknowledged], keeps the vehicle from arming; `null` when it lets it. */
internal fun PreflightCheck.armingUnavailable(acknowledged: PreflightAcknowledgements): CommandUnavailable? =
    when (val verdict = verdict(acknowledged)) {
        is PreflightVerdict.Blocked -> CommandUnavailable.PreflightRed(verdict.reds.size)
        is PreflightVerdict.NeedsAcknowledgement -> CommandUnavailable.CautionsToAcknowledge
        PreflightVerdict.Clear -> null
    }

/** Arm held only by the pre-flight check: the operator opens the checklist to see why, and to acknowledge. */
internal val OfferedCommand.opensChecklist: Boolean
    get() = command == VehicleCommand.ARM &&
        (unavailable is CommandUnavailable.PreflightRed || unavailable == CommandUnavailable.CautionsToAcknowledge)

/** Whether a tap opens its sheet: confirmable now, or arm whose sheet says what holds it. */
internal val OfferedCommand.canBeRequested: Boolean
    get() = unavailable == null || opensChecklist

private fun PreflightColour.rank(): Int = when (this) {
    PreflightColour.RED -> 0
    PreflightColour.AMBER -> 1
    PreflightColour.GREEN -> 2
}
