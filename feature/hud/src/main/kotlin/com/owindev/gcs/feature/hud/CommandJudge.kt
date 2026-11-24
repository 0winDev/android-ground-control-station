package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.time.DeviceClock
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.RouteRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Judges the command bar and arm's pre-flight checklist from one check (REQ-048, REQ-051), with the amber items the
 * operator acknowledged. Each judgement drops the acknowledgements its check no longer covers, so an item that changed
 * needs a new one even if it changes back; nothing judges while nobody looks, so the screen forgets them then.
 */
internal class CommandJudge(
    private val preflight: PreflightRepository,
    private val rules: CommandRules,
    private val deviceClock: DeviceClock,
) {
    private val routeRule = RouteRule(rules.linkLoss)
    private val mutableAcknowledgements = MutableStateFlow(PreflightAcknowledgements())

    val acknowledgements: StateFlow<PreflightAcknowledgements> = mutableAcknowledgements.asStateFlow()

    fun judge(
        key: CommandKey?,
        route: VehicleRoute?,
        vehicles: Map<SystemId, Vehicle>,
        telemetry: Telemetry?,
    ): Judged? = key?.let {
        val check = preflight.checkFor(it.systemId)
        val acknowledged = mutableAcknowledgements.updateAndGet { current -> current.keptFor(check) }
        Judged(
            key = it,
            bar = commandBarOf(it, vehicles[it.systemId], telemetry, check, acknowledged, rules)
                ?.heldBy(routeRule.statusOn(it, route)),
            checklist = check.toChecklist(acknowledged),
        )
    }

    /** Acknowledges [item] only if a fresh check for [key] still has it amber, reason and all (#56). */
    fun acknowledge(item: PreflightItem, key: CommandKey) {
        val check = preflight.checkFor(key.systemId)
        mutableAcknowledgements.update { it.keptFor(check).acknowledge(item, check, deviceClock.markNow()) }
    }

    /** After an accepted arm, or while unobserved: the next arm needs the checklist read again. */
    fun forget() {
        mutableAcknowledgements.value = PreflightAcknowledgements()
    }
}

/** What the bar and the checklist show for [key], from one check. */
internal class Judged(val key: CommandKey, val bar: CommandBar?, val checklist: List<ChecklistItem>)
