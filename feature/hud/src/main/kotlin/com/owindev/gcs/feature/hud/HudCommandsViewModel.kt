package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The flight screen's commands (REQ-050): what is offered, its confirmation and the result of the last one sent. Two
 * steps, as every dangerous command: a request opens the sheet, a slide confirms it. Every rule is judged again with
 * fresh values when the operator acts, never on the state last drawn: a tap or a slide drawn a frame earlier confirms
 * nothing that is no longer offered. The command service judges everything once more under its send lock.
 */
// One source per part of what a command needs, and the rules the service applies; grouping them would only hide that.
@Suppress("LongParameterList")
@HiltViewModel
class HudCommandsViewModel @Inject constructor(
    private val commands: CommandRepository,
    private val vehicles: VehicleRepository,
    private val telemetry: TelemetryRepository,
    private val preflight: PreflightRepository,
    private val altitudeRule: TakeOffAltitudeRule,
    linkLoss: LinkLossRule,
    landed: LandedRule,
    groundPosition: GroundPositionRule,
) : ViewModel() {

    private val rules = CommandRules(linkLoss, landed, groundPosition)
    private val request = MutableStateFlow<Request?>(null)
    private val sent = MutableStateFlow<Sent?>(null)

    /** The landed state and the position count for 0.5 s, so the bar is judged again at twice that rate. */
    private val ticks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(RECHECK)
        }
    }

    private val bar: Flow<Pair<CommandKey?, CommandBar?>> =
        combine(commands.commandKey, vehicles.vehicles, telemetry.activeTelemetry, ticks) {
                key,
                vehicles,
                telemetry,
                _,
            ->
            key to barOf(key, vehicles, telemetry)
        }

    val state: StateFlow<CommandsUiState> = combine(bar, request, sent, commands.pending) {
            (key, bar),
            request,
            sent,
            pending,
        ->
        CommandsUiState(
            bar = bar,
            sheet = if (key != null && bar != null) request?.toSheet(key, bar) else null,
            feedback = sent?.toFeedback(pending),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout = STOP_TIMEOUT), CommandsUiState())

    /** Opens the confirmation of [command], only if it is offered and can be confirmed right now. */
    fun onCommandRequested(command: VehicleCommand) {
        val key = commands.commandKey.value ?: return
        if (!canConfirm(command)) return
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
     * offered, or if the altitude changed since the slide started (it asks for another slide).
     */
    fun onCommandConfirmed(key: ConfirmKey) {
        val confirming = request.value?.takeIf { canConfirm(it.command) } ?: return
        if (key.altitudeMetres != confirming.altitudeMetres) {
            request.value = confirming.copy(slideAgain = true)
        } else {
            request.value = null
            launchSend(confirming, key)
        }
    }

    /** Hides an ended command's result; one still waiting for its answer stays. */
    fun onFeedbackDismissed() {
        sent.update { current -> current?.takeIf { it.result == null } }
    }

    private fun launchSend(confirming: Request, key: ConfirmKey) {
        val sending = Sent(confirming.command, confirming.openedOn.systemId)
        sent.value = sending
        viewModelScope.launch {
            val result = send(confirming.command, key) ?: return@launch
            if (result == CommandResult.NotSent(NotSentReason.KeyChanged)) {
                sent.compareAndSet(sending, null)
                commands.commandKey.value?.let { now ->
                    request.value = Request(confirming.command, now, key.altitudeMetres, slideAgain = true)
                }
            } else {
                sent.compareAndSet(sending, sending.ended(result))
            }
        }
    }

    private suspend fun send(command: VehicleCommand, key: ConfirmKey): CommandResult? = when (command) {
        // Offered only with no amber item, so there is nothing to acknowledge (#106 brings the checklist).
        VehicleCommand.ARM -> commands.arm(key.commandKey, PreflightAcknowledgements())

        VehicleCommand.TAKE_OFF -> key.altitudeMetres?.let { commands.takeOff(key.commandKey, it) }

        VehicleCommand.RETURN_TO_LAUNCH -> commands.returnToLaunch(key.commandKey)

        VehicleCommand.LAND -> commands.land(key.commandKey)
    }

    /** Judged on fresh values, never on the state last drawn. */
    private fun canConfirm(command: VehicleCommand): Boolean =
        barOf(commands.commandKey.value, vehicles.vehicles.value, telemetry.activeTelemetry.value)
            ?.offered(command)
            ?.let { it.unavailable == null } == true

    private fun barOf(key: CommandKey?, vehicles: Map<SystemId, Vehicle>, telemetry: Telemetry?): CommandBar? =
        key?.let { commandBarOf(it, vehicles[it.systemId], telemetry, preflight.checkFor(it.systemId), rules) }

    /** The command whose confirmation is open, on the link it was opened on. */
    private data class Request(
        val command: VehicleCommand,
        val openedOn: CommandKey,
        val altitudeMetres: Float?,
        val slideAgain: Boolean = false,
    ) {

        /** Shown only on the link it was opened on, and while its command is still on the bar. */
        fun toSheet(key: CommandKey, bar: CommandBar): CommandSheet? =
            bar.offered(command)?.takeIf { key == openedOn }?.let { offered ->
                CommandSheet(
                    command = command,
                    systemId = key.systemId.value,
                    altitudeMetres = altitudeMetres,
                    key = ConfirmKey(key, altitudeMetres).takeIf { offered.unavailable == null },
                    unavailable = offered.unavailable,
                    slideAgain = slideAgain,
                )
            }
    }

    /** One command sent; a class, so a newer one is never taken for it. */
    private class Sent(val command: VehicleCommand, val systemId: SystemId, val result: CommandResult? = null) {

        fun ended(result: CommandResult) = Sent(command, systemId, result)

        fun toFeedback(pending: PendingCommand?): CommandFeedback {
            val status = when {
                result != null -> messageOf(command, result)?.let(CommandStatus::Ended)
                pending?.command == command && pending.systemId == systemId -> pending.state.toStatus(command)
                else -> null
            } ?: CommandStatus.Sending(attempt = null, attempts = command.attempts())
            return CommandFeedback(command, status)
        }
    }

    private companion object {
        val RECHECK: Duration = 250.milliseconds

        /** Survives a configuration change. */
        val STOP_TIMEOUT: Duration = 5.seconds
    }
}
