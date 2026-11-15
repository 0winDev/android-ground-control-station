package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.core.domain.battery.BatteryThresholds
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightCheck
import com.owindev.gcs.core.domain.preflight.PreflightInput
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.preflight.PreflightRule
import com.owindev.gcs.core.domain.preflight.PreflightVerdict
import com.owindev.gcs.core.domain.preflight.verdict
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.message.MessageStore
import com.owindev.gcs.data.vehicle.parameter.ParameterStore
import com.owindev.gcs.data.vehicle.parameter.batteryThresholdsOf
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import javax.inject.Inject

/** Why an arm or a take off must not leave now (REQ-048), `null` when it may; RTL and land never ask. */
internal interface PreflightVeto {

    fun refusalToArm(systemId: SystemId, acknowledged: PreflightAcknowledgements): NotSentReason?

    /** Only red items: the vehicle is armed by then, and the ambers were acknowledged to arm it. */
    fun refusalToTakeOff(systemId: SystemId): NotSentReason?
}

/**
 * Judges the pre-flight check right when asked, from the stores the link listener fills, never from a flow the screen
 * observes (lesson #11), and only for the active vehicle, so every part of the input is that vehicle's in this session.
 * The alerts add what they hold only while someone observes them; the check judges their causes on its own.
 */
// One source per part of the check's input, read together as one snapshot; grouping them would only rename them.
@Suppress("LongParameterList")
internal class PreflightGate @Inject constructor(
    private val vehicles: DefaultVehicleRepository,
    private val activeVehicle: ActiveVehicle,
    private val session: LinkSession,
    private val telemetry: TelemetryStore,
    private val parameters: ParameterStore,
    private val messages: MessageStore,
    private val uplink: DefaultUplinkRepository,
    private val background: BackgroundLinkRepository,
    private val alerts: AlertRepository,
    private val rule: PreflightRule,
) : PreflightVeto,
    PreflightRepository {

    override fun refusalToArm(systemId: SystemId, acknowledged: PreflightAcknowledgements): NotSentReason? =
        when (val verdict = checkFor(systemId).verdict(acknowledged)) {
            is PreflightVerdict.Blocked -> NotSentReason.PreflightRed(verdict.reds)
            is PreflightVerdict.NeedsAcknowledgement -> NotSentReason.PreflightUnacknowledged(verdict.ambers)
            PreflightVerdict.Clear -> null
        }

    override fun refusalToTakeOff(systemId: SystemId): NotSentReason? =
        checkFor(systemId).reds.takeIf { it.isNotEmpty() }?.let(NotSentReason::PreflightRed)

    override fun checkFor(systemId: SystemId): PreflightCheck {
        val current = session.session.value
        val active = activeVehicle.systemId.value == systemId
        val followedParameters = parameters.parameters.value
        val followedMessages = messages.messages.value
        val parametersFollowed = followedParameters.session == current && followedParameters.systemId == systemId
        val messagesFollowed = followedMessages.session == current && followedMessages.systemId == systemId
        return rule.check(
            PreflightInput(
                connection = vehicles.activeConnection,
                vehicle = vehicles.vehicles.value[systemId]?.takeIf { active },
                telemetry = telemetry.telemetry.value[systemId]?.takeIf { active },
                battery = if (active && parametersFollowed) {
                    batteryThresholdsOf(followedParameters.values)
                } else {
                    BatteryThresholds.UNKNOWN
                },
                uplink = uplink.health.value,
                background = background.status.value,
                preArmReason = followedMessages.preArmReason.takeIf { active && messagesFollowed },
                alerts = alerts.activeAlerts.value,
            ),
        )
    }
}

/** These routes, then [refusal] for a route that is open; the whole is checked again under the sender's lock. */
internal fun CommandRoutes.vetoedBy(refusal: (SystemId) -> NotSentReason?): CommandRoutes = VetoedRoutes(this, refusal)

// A class, not a fun-interface lambda: lint's ExperimentalDetector crashes on one (#99).
private class VetoedRoutes(private val routes: CommandRoutes, private val refusal: (SystemId) -> NotSentReason?) :
    CommandRoutes {

    override fun routeTo(systemId: SystemId): CommandRoute {
        val route = routes.routeTo(systemId)
        return if (route is CommandRoute.Open) refusal(systemId)?.let(CommandRoute::Closed) ?: route else route
    }
}
