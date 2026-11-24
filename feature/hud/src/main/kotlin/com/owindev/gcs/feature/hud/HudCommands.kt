package com.owindev.gcs.feature.hud

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightCheck
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.Rejection
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand

/** What the operator does with the commands; each is judged again by [HudCommandsViewModel]. */
@Immutable
internal data class CommandActions(
    val onRequest: (VehicleCommand) -> Unit = {},
    val onAltitudeChanged: (Float) -> Unit = {},
    val onConfirm: (ConfirmKey) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissFeedback: () -> Unit = {},
    val onDismissRefusal: () -> Unit = {},
    val onReviewRoute: () -> Unit = {},
    val onConfirmRoute: (RouteOffer) -> Unit = {},
    val onCancelRoute: () -> Unit = {},
    val onAcknowledgeCaution: (PreflightItem) -> Unit = {},
)

/** The flight screen's commands (REQ-050): [ground] (arm or take off) kept apart from [returnToLaunch] and [land]. */
data class CommandBar(val ground: OfferedCommand, val returnToLaunch: OfferedCommand, val land: OfferedCommand)

/** A command on the bar; it can be confirmed only while [unavailable] is `null`. */
data class OfferedCommand(val command: VehicleCommand, val unavailable: CommandUnavailable? = null)

/** Why a command is offered but cannot be confirmed now; the command service judges it again before sending. */
sealed interface CommandUnavailable {

    /** Arm or take off, for a vehicle not known to be on the ground. */
    data object NotOnGround : CommandUnavailable

    /** RTL or land, for a vehicle that is not armed. */
    data object NotArmed : CommandUnavailable

    data class PreflightRed(val items: Int) : CommandUnavailable

    /** Arm, while an amber pre-flight item has no acknowledgement; the checklist acknowledges them. */
    data object CautionsToAcknowledge : CommandUnavailable

    /** The vehicle is heard only from a new route, which the operator has not accepted (REQ-051). */
    data object RouteChanged : CommandUnavailable

    /** The vehicle is heard from more than one route: nothing can be accepted until one is heard alone (REQ-051). */
    data object RouteAlternating : CommandUnavailable

    /** No route is known yet on the current key, as for a moment after it moves (REQ-051). */
    data object RouteUnknown : CommandUnavailable
}

/**
 * The slide's key: the [command] it confirms, what the command service compares ([commandKey]) and the take off
 * altitude chosen, so changing the altitude during a slide drops it and the altitude sent is the one it confirmed.
 */
data class ConfirmKey(val command: VehicleCommand, val commandKey: CommandKey, val altitudeMetres: Float?)

/**
 * The confirmation of [command] for [systemId] (REQ-050), only while the link it was opened on lasts. [key] is `null`
 * and [unavailable] set while it cannot be confirmed; [slideAgain] after a slide the command service found outdated.
 * Arm's carries the pre-flight [checklist] (REQ-048).
 */
data class CommandSheet(
    val command: VehicleCommand,
    val systemId: Int,
    val altitudeMetres: Float?,
    val key: ConfirmKey?,
    val unavailable: CommandUnavailable?,
    val slideAgain: Boolean = false,
    val checklist: List<ChecklistItem>? = null,
)

/** The command sent last and how it stands, apart from the sheet, so RTL and land stay reachable meanwhile. */
data class CommandFeedback(val command: VehicleCommand, val status: CommandStatus)

sealed interface CommandStatus {

    /** Waiting for its answer: sent the [attempt]th time of [attempts], or `null` before it leaves. */
    data class Sending(val attempt: Int?, val attempts: Int) : CommandStatus

    /** The vehicle said it started; [percent] is its progress, `null` when it gave none. */
    data class InProgress(val percent: Int?) : CommandStatus

    data class Ended(val message: CommandMessage) : CommandStatus
}

/**
 * A result in words: [text] with [args], each a string resource or a value, at its alert [level]; [text] is a plural
 * resource when [quantity] is set.
 */
data class CommandMessage(
    val text: Int,
    val args: List<Any> = emptyList(),
    val level: Level,
    val quantity: Int? = null,
) {

    enum class Level {
        NOMINAL,
        ADVISORY,
        CAUTION,
        EMERGENCY,
    }
}

/** A string resource passed as an argument of a [CommandMessage]. */
data class TextArg(@param:StringRes val text: Int)

/**
 * What the flight screen shows of the commands; empty during a replay, with no vehicle or while it is lost. [refusal]
 * is a command refused before it left while [feedback] still held one the operator must see.
 */
data class CommandsUiState(
    val bar: CommandBar? = null,
    val sheet: CommandSheet? = null,
    val feedback: CommandFeedback? = null,
    val refusal: CommandFeedback? = null,
)

/**
 * The bar for [vehicle], heard live with [key]: arm while disarmed and take off while armed, each only on the ground and
 * with no red pre-flight item, arm also with every amber one [acknowledged] (REQ-044, REQ-048); RTL and land while
 * armed, whatever the alerts or the pre-flight check.
 */
// One check per rule the command service applies; splitting them would scatter what the bar promises.
@Suppress("LongParameterList")
internal fun commandBarOf(
    key: CommandKey,
    vehicle: Vehicle?,
    telemetry: Telemetry?,
    check: PreflightCheck,
    acknowledged: PreflightAcknowledgements,
    rules: CommandRules,
): CommandBar? {
    if (vehicle == null || vehicle.state.systemId != key.systemId || rules.linkLoss.isLost(vehicle)) {
        return null
    }
    val armed = vehicle.state.armed
    val reds = check.reds
    val onGround = rules.landed.isOnGround(telemetry)
    val ground = if (armed) {
        OfferedCommand(
            VehicleCommand.TAKE_OFF,
            when {
                !onGround || !rules.groundPosition.isNearGround(telemetry) -> CommandUnavailable.NotOnGround
                reds.isNotEmpty() -> CommandUnavailable.PreflightRed(reds.size)
                else -> null
            },
        )
    } else {
        OfferedCommand(
            VehicleCommand.ARM,
            when {
                !onGround -> CommandUnavailable.NotOnGround
                else -> check.armingUnavailable(acknowledged)
            },
        )
    }
    val inFlightUnavailable = CommandUnavailable.NotArmed.takeUnless { armed }
    return CommandBar(
        ground = ground,
        returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH, inFlightUnavailable),
        land = OfferedCommand(VehicleCommand.LAND, inFlightUnavailable),
    )
}

/** The rules [commandBarOf] applies, the command service's own. */
internal data class CommandRules(
    val linkLoss: LinkLossRule,
    val landed: LandedRule,
    val groundPosition: GroundPositionRule,
)

internal fun CommandBar.offered(command: VehicleCommand): OfferedCommand? =
    listOf(ground, returnToLaunch, land).firstOrNull { it.command == command }

/** RTL and land are sent up to three times, arm and take off once (ADR 0008). */
internal fun VehicleCommand.attempts(): Int = when (this) {
    VehicleCommand.RETURN_TO_LAUNCH, VehicleCommand.LAND -> RETRIED_ATTEMPTS
    VehicleCommand.ARM, VehicleCommand.TAKE_OFF -> 1
}

internal fun PendingState.toStatus(command: VehicleCommand): CommandStatus = when (this) {
    is PendingState.Waiting -> CommandStatus.Sending(attempt, command.attempts())
    is PendingState.InProgress -> CommandStatus.InProgress(percent)
}

/**
 * [result] in words for [command] (REQ-042, REQ-044); [otherPending] tells a refusal as busy while another command waits
 * from one while the last one's late answer is still listened for (ADR 0008).
 */
internal fun messageOf(command: VehicleCommand, result: CommandResult, otherPending: Boolean): CommandMessage {
    val name = TextArg(command.title())
    return when (result) {
        CommandResult.Accepted -> CommandMessage(
            R.string.hud_command_accepted,
            listOf(name),
            CommandMessage.Level.NOMINAL,
        )

        CommandResult.Busy -> CommandMessage(R.string.hud_command_busy, listOf(name), CommandMessage.Level.CAUTION)

        is CommandResult.Rejected -> CommandMessage(
            R.string.hud_command_rejected,
            listOf(name, result.reason ?: TextArg(result.rejection.text())),
            CommandMessage.Level.EMERGENCY,
        )

        is CommandResult.NoAnswer -> noAnswer(command, result, name)

        is CommandResult.ModeNotChanged -> modeNotChanged(result.modeChange)

        is CommandResult.TakeOffStopped -> CommandMessage(
            R.string.hud_command_take_off_stopped,
            listOf(TextArg(result.reason.text(otherPending))),
            CommandMessage.Level.EMERGENCY,
        )

        CommandResult.Superseded ->
            CommandMessage(R.string.hud_command_superseded, listOf(name), CommandMessage.Level.ADVISORY)

        is CommandResult.NotSent -> CommandMessage(
            R.string.hud_command_not_sent,
            listOf(name, TextArg(result.reason.text(otherPending))),
            CommandMessage.Level.CAUTION,
        )
    }
}

private fun noAnswer(command: VehicleCommand, result: CommandResult.NoAnswer, name: TextArg): CommandMessage {
    val stopped = result.retriesStopped
    return when {
        result.startedByVehicle ->
            CommandMessage(R.string.hud_command_started_no_end, listOf(name), CommandMessage.Level.EMERGENCY)

        stopped != null -> CommandMessage(
            R.plurals.hud_command_no_answer_stopped,
            listOf(name, result.attempts, TextArg(stopped.text(otherPending = false))),
            CommandMessage.Level.EMERGENCY,
            quantity = result.attempts,
        )

        command.attempts() > 1 -> CommandMessage(
            R.plurals.hud_command_no_answer,
            listOf(name, result.attempts),
            CommandMessage.Level.EMERGENCY,
            quantity = result.attempts,
        )

        else -> CommandMessage(R.string.hud_command_no_answer_not_resent, listOf(name), CommandMessage.Level.EMERGENCY)
    }
}

/** The vehicle stays in its mode when it refused GUIDED; otherwise it may be in GUIDED, or switch to it later. */
private fun modeNotChanged(modeChange: CommandResult?): CommandMessage = when (modeChange) {
    null -> CommandMessage(R.string.hud_command_guided_not_shown, level = CommandMessage.Level.EMERGENCY)

    is CommandResult.NoAnswer -> CommandMessage(
        R.string.hud_command_guided_no_answer,
        level = CommandMessage.Level.EMERGENCY,
    )

    is CommandResult.Rejected -> CommandMessage(
        R.string.hud_command_guided_refused,
        listOf(modeChange.reason ?: TextArg(modeChange.rejection.text())),
        CommandMessage.Level.EMERGENCY,
    )

    else -> CommandMessage(
        R.string.hud_command_guided_refused,
        listOf(TextArg(R.string.hud_rejection_busy)),
        CommandMessage.Level.EMERGENCY,
    )
}

@StringRes
internal fun VehicleCommand.title(): Int = when (this) {
    VehicleCommand.ARM -> R.string.hud_command_arm
    VehicleCommand.TAKE_OFF -> R.string.hud_command_take_off
    VehicleCommand.RETURN_TO_LAUNCH -> R.string.hud_command_rtl
    VehicleCommand.LAND -> R.string.hud_command_land
}

@StringRes
private fun Rejection.text(): Int = when (this) {
    Rejection.DENIED -> R.string.hud_rejection_denied

    Rejection.UNSUPPORTED -> R.string.hud_rejection_unsupported

    Rejection.FAILED -> R.string.hud_rejection_failed

    Rejection.CANCELLED -> R.string.hud_rejection_cancelled

    Rejection.COMMAND_LONG_ONLY, Rejection.COMMAND_INT_ONLY, Rejection.UNSUPPORTED_FRAME ->
        R.string.hud_rejection_unsupported

    Rejection.NOT_IN_CONTROL -> R.string.hud_rejection_not_in_control

    Rejection.UNKNOWN -> R.string.hud_rejection_unknown
}

@StringRes
private fun NotSentReason.text(otherPending: Boolean): Int = when (this) {
    NotSentReason.Replay -> R.string.hud_not_sent_replay
    NotSentReason.NotActiveVehicle -> R.string.hud_not_sent_not_active
    NotSentReason.LinkLost -> R.string.hud_not_sent_link_lost
    NotSentReason.RouteChanged -> R.string.hud_not_sent_route_changed
    NotSentReason.KeyChanged -> R.string.hud_not_sent_key_changed
    NotSentReason.NotArmed -> R.string.hud_not_sent_not_armed
    NotSentReason.NotLanded -> R.string.hud_not_sent_not_landed
    NotSentReason.AltitudeOutOfRange -> R.string.hud_not_sent_altitude
    NotSentReason.Busy -> if (otherPending) R.string.hud_not_sent_busy else R.string.hud_not_sent_late_window
    NotSentReason.SendFailed -> R.string.hud_not_sent_send_failed
    is NotSentReason.PreflightRed -> R.string.hud_not_sent_preflight_red
    is NotSentReason.PreflightUnacknowledged -> R.string.hud_not_sent_preflight_unacknowledged
}

private const val RETRIED_ATTEMPTS = 3
