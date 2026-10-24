package com.owindev.gcs.core.domain.attention

import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle

/** What holds the operator on the flight screen now: a live vehicle armed, and the live emergencies raised. */
data class FlightAttention(val liveArmed: Boolean, val emergencies: Set<Emergency>)

/** A live emergency, told apart from any other so a new one is never taken for one already seen. */
sealed interface Emergency {

    /** The live link to [systemId] is lost (REQ-017), the emergency that outranks every alert. */
    data class LinkLost(val systemId: SystemId) : Emergency

    /** An emergency alert raised as [occurrence] (REQ-028). */
    data class Raised(val occurrence: Long) : Emergency
}

/**
 * When the operator may leave the flight screen and when the app takes them back to it (ADR 0007). Only live
 * vehicles and alerts count: a replay's are recorded and never call the operator back (REQ-037).
 *
 * The operator may leave only while no live vehicle is armed, lost or not, as it was last heard. The app returns to
 * the flight screen when a live vehicle is armed or an emergency appears that was not there before; one already seen
 * never keeps the operator from the recordings, which a replay after a lost link needs.
 */
class FlightAttentionRule(private val linkLossRule: LinkLossRule) {

    fun attentionOf(vehicles: Collection<Vehicle>, alerts: Alerts): FlightAttention {
        val live = vehicles.filter { it.origin == DataOrigin.LIVE }
        val lost = live.filter(linkLossRule::isLost).map { Emergency.LinkLost(it.state.systemId) }
        val raised = (alerts as? Alerts.Evaluated)?.active.orEmpty()
            .filter { it.origin == DataOrigin.LIVE && it.alert.level == AlertLevel.EMERGENCY }
            .map { Emergency.Raised(it.occurrence) }
        return FlightAttention(liveArmed = live.any { it.state.armed }, emergencies = (lost + raised).toSet())
    }

    fun mayLeave(attention: FlightAttention): Boolean = !attention.liveArmed

    /** Whether going from [before] to [now] must take the operator back to the flight screen. */
    fun returnsToFlight(before: FlightAttention, now: FlightAttention): Boolean =
        now.liveArmed || !before.emergencies.containsAll(now.emergencies)
}
