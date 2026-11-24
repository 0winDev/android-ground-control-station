package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.time.DeviceClock
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.CommandRouteRepository
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The flight screen's commands (REQ-050): what is offered, its confirmation and the result of the last one sent. Two
 * steps, as every dangerous command: a request opens the sheet, a slide confirms it. Every rule is judged again with
 * fresh values when the operator acts, never on the state last drawn: a tap or a slide drawn a frame earlier confirms
 * nothing that is no longer offered. The command service judges everything once more under its send lock. Every
 * command is held while the vehicle's route is not the one commands take (REQ-051). Arm's sheet is the pre-flight
 * checklist, where the operator acknowledges each amber item; the acknowledgements go with the arm (REQ-048).
 */
// One source per part of what a command needs, and the rules the service applies; grouping them would only hide that.
@Suppress("LongParameterList")
@HiltViewModel
class HudCommandsViewModel @Inject constructor(
    private val commands: CommandRepository,
    private val routes: CommandRouteRepository,
    private val vehicles: VehicleRepository,
    private val telemetry: TelemetryRepository,
    private val preflight: PreflightRepository,
    private val altitudeRule: TakeOffAltitudeRule,
    deviceClock: DeviceClock,
    linkLoss: LinkLossRule,
    landed: LandedRule,
    groundPosition: GroundPositionRule,
) : ViewModel() {

    private val judge = CommandJudge(preflight, CommandRules(linkLoss, landed, groundPosition), deviceClock)
    private val request = MutableStateFlow<Request?>(null)
    private val sent = MutableStateFlow<Sent?>(null)
    private val refused = MutableStateFlow<Refusal?>(null)

    /** The landed state and the position count for 0.5 s, so the bar is judged again at twice that rate. */
    private val ticks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(RECHECK)
        }
    }

    private val rechecks: Flow<Unit> = combine(ticks, judge.acknowledgements) { _, _ -> }

    private val judged: Flow<Judged?> =
        combine(commands.commandKey, routes.route, vehicles.vehicles, telemetry.activeTelemetry, rechecks) {
                key,
                route,
                vehicles,
                telemetry,
                _,
            ->
            judge.judge(key, route, vehicles, telemetry)
        }

    val state: StateFlow<CommandsUiState> = combine(judged, request, sent, refused, commands.pending) {
            judged,
            opened,
            sent,
            refused,
            pending,
        ->
        val key = judged?.key
        val bar = judged?.bar
        val sheet = judged?.let { opened?.toSheet(it) }
        // A sheet that could not be shown once is over: it never comes back by itself when its command does.
        if (opened != null && sheet == null) request.compareAndSet(opened, null)
        CommandsUiState(
            bar = bar,
            sheet = sheet,
            feedback = sent?.toFeedback(pending, key),
            refusal = refused?.takeIf { it.systemId == key?.systemId }?.feedback,
        )
    }
        // Nobody looks for a while: whatever was open was not confirmed, and never reopens by itself.
        .onCompletion { request.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout = STOP_TIMEOUT), CommandsUiState())

    /**
     * Opens the confirmation of [command], only if it is offered and can be confirmed right now; arm also while only the
     * pre-flight check holds it, since its sheet is the checklist that says why.
     */
    fun onCommandRequested(command: VehicleCommand) {
        val key = commands.commandKey.value
        if (key == null || offeredNow(command)?.canBeRequested != true) return
        val altitude = if (command == VehicleCommand.TAKE_OFF) altitudeRule.defaultMetres else null
        request.value = Request(command, openedOn = key, altitudeMetres = altitude)
    }

    /** A new altitude is a new key: a slide under way is dropped and the next one confirms this altitude. */
    fun onTakeOffAltitudeChanged(metres: Float) {
        request.update { current ->
            current?.takeIf { it.command == VehicleCommand.TAKE_OFF }
                ?.copy(altitudeMetres = metres.coerceIn(altitudeRule.minMetres, altitudeRule.maxMetres))
                ?: current
        }
    }

    fun onCommandCancelled() {
        request.value = null
    }

    /**
     * Sends the command the slide confirmed on [key], with [key]'s altitude: nothing if the sheet's command is no longer
     * offered, and another slide if [key] is not the open sheet's (its command, link or altitude changed meanwhile).
     */
    fun onCommandConfirmed(key: ConfirmKey) {
        val confirming = request.value?.takeIf { offeredNow(it.command)?.unavailable == null } ?: return
        if (key != confirming.confirmKey()) {
            request.value = confirming.copy(slideAgain = true)
        } else {
            request.value = null
            launchSend(confirming, key)
        }
    }

    /**
     * Acknowledges the amber [item] the open checklist showed, judged on a fresh check: an item that changed or cleared
     * meanwhile is not that check's, so a tap meant for it acknowledges nothing (#56).
     */
    fun onCautionAcknowledged(item: PreflightItem) {
        val key = commands.commandKey.value ?: return
        val open = request.value?.let { it.command == VehicleCommand.ARM && it.openedOn == key } == true
        if (!open || offeredNow(VehicleCommand.ARM)?.canBeRequested != true) return
        judge.acknowledge(item, key)
    }

    /** Hides an ended command's result; one still waiting for its answer stays. */
    fun onFeedbackDismissed() {
        sent.update { current -> current?.takeIf { it.message == null } }
    }

    fun onRefusalDismissed() {
        refused.value = null
    }

    private fun launchSend(confirming: Request, key: ConfirmKey) {
        val replaced = sent.value
        val sending = Sent(confirming.command, confirming.openedOn)
        sent.value = sending
        refused.value = null
        viewModelScope.launch {
            val result = send(confirming.command, key)
            val outdated = result == CommandResult.NotSent(NotSentReason.KeyChanged)
            val now = commands.commandKey.value
            val otherPending = commands.pending.value != null
            when {
                // Another slide, but only for the same vehicle: never one it was not confirmed for.
                outdated && now?.systemId == key.commandKey.systemId -> {
                    sent.compareAndSet(sending, replaced)
                    request.value = Request(confirming.command, now, key.altitudeMetres, slideAgain = true)
                }

                // A refusal never hides the command still waiting, nor an emergency the operator has not dismissed.
                result is CommandResult.NotSent && replaced?.mustStay() == true -> {
                    sent.compareAndSet(sending, replaced)
                    refused.value = Refusal(key.commandKey.systemId, confirming.command, result.reason, otherPending)
                }

                else -> sent.compareAndSet(sending, sending.ended(result, otherPending))
            }
        }
    }

    private suspend fun send(command: VehicleCommand, key: ConfirmKey): CommandResult = when (command) {
        VehicleCommand.ARM -> commands.arm(key.commandKey, judge.acknowledgements.value)

        VehicleCommand.TAKE_OFF -> key.altitudeMetres?.let { commands.takeOff(key.commandKey, it) }
            ?: CommandResult.NotSent(NotSentReason.AltitudeOutOfRange)

        VehicleCommand.RETURN_TO_LAUNCH -> commands.returnToLaunch(key.commandKey)

        VehicleCommand.LAND -> commands.land(key.commandKey)
    }

    /** Judged on fresh values, never on the state last drawn. */
    private fun offeredNow(command: VehicleCommand): OfferedCommand? = judge.judge(
        commands.commandKey.value,
        routes.route.value,
        vehicles.vehicles.value,
        telemetry.activeTelemetry.value,
    )
        ?.bar
        ?.offered(command)

    /** The command whose confirmation is open, on the link it was opened on. */
    private data class Request(
        val command: VehicleCommand,
        val openedOn: CommandKey,
        val altitudeMetres: Float?,
        val slideAgain: Boolean = false,
    ) {

        fun confirmKey() = ConfirmKey(command, openedOn, altitudeMetres)

        /** Shown only on the link it was opened on, and while its command is still on the bar. */
        fun toSheet(judged: Judged): CommandSheet? =
            judged.bar?.offered(command)?.takeIf { judged.key == openedOn }?.let { offered ->
                CommandSheet(
                    command = command,
                    systemId = openedOn.systemId.value,
                    altitudeMetres = altitudeMetres,
                    key = confirmKey().takeIf { offered.unavailable == null },
                    unavailable = offered.unavailable,
                    slideAgain = slideAgain,
                    checklist = judged.checklist.takeIf { command == VehicleCommand.ARM },
                )
            }
    }

    /** One command sent on [sentOn], and its result in words once it ended; a class, so a newer one is never taken for it. */
    private class Sent(val command: VehicleCommand, val sentOn: CommandKey, val message: CommandMessage? = null) {

        fun ended(result: CommandResult, otherPending: Boolean) =
            Sent(command, sentOn, messageOf(command, result, otherPending))

        fun mustStay(): Boolean = message == null || message.level == CommandMessage.Level.EMERGENCY

        /**
         * Shown only for its own vehicle; a result that needs no action ends with the connection it was sent on, the
         * others stay until the operator dismisses them.
         */
        fun toFeedback(pending: PendingCommand?, key: CommandKey?): CommandFeedback? {
            val current = key?.systemId == sentOn.systemId &&
                (message == null || message.level > CommandMessage.Level.ADVISORY || key == sentOn)
            if (!current) return null
            val status = message?.let(CommandStatus::Ended)
                ?: pending?.takeIf { it.command == command && it.systemId == sentOn.systemId }?.state?.toStatus(command)
                ?: CommandStatus.Sending(attempt = null, attempts = command.attempts())
            return CommandFeedback(command, status)
        }
    }

    /** A command refused before it left, shown apart so it never hides the one that is still the operator's concern. */
    private class Refusal(
        val systemId: SystemId,
        command: VehicleCommand,
        reason: NotSentReason,
        otherPending: Boolean,
    ) {
        val feedback = CommandFeedback(
            command,
            CommandStatus.Ended(messageOf(command, CommandResult.NotSent(reason), otherPending)),
        )
    }

    private companion object {
        val RECHECK: Duration = 250.milliseconds

        /** Survives a configuration change. */
        val STOP_TIMEOUT: Duration = 5.seconds
    }
}
