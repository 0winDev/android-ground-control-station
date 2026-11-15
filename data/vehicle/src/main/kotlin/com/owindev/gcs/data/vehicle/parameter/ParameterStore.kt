package com.owindev.gcs.data.vehicle.parameter

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [BatteryParameter]s the active vehicle's autopilot (component 1) has sent in this link session (REQ-028), each
 * applied as it arrives, asked for or announced unasked after a change on the vehicle: PARAM_VALUE is broadcast, so it
 * is matched by system ID, component and name (mavlink.io `services/parameter.html`).
 *
 * A PARAM_VALUE whose `param_id` is not 7-bit ASCII, which the parameter protocol requires, is ignored and counted in
 * [malformed]; so is one of these parameters with another type, or a value its type cannot hold. Values are forgotten
 * when the vehicle reboots, since they may have changed (#69), and with the session, as the messages are (REQ-037).
 *
 * Written by the vehicle repository's listener and watchdog, hence the lock. Nothing is allocated for a message that is
 * not a PARAM_VALUE, and nothing received is logged.
 */
@Singleton
internal class ParameterStore @Inject constructor(private val activeVehicle: ActiveVehicle) {

    private val lock = Any()

    // Guarded by lock.
    private var session: SessionId = FIRST_SESSION
    private var malformedCount = 0

    private val mutableParameters = MutableStateFlow(FollowedParameters.empty(FIRST_SESSION))

    val parameters: StateFlow<FollowedParameters> = mutableParameters.asStateFlow()

    /** PARAM_VALUEs ignored as malformed since the app started. */
    val malformed: Int
        get() = synchronized(lock) { malformedCount }

    fun record(received: ReceivedMessage) {
        val message = received.message as? ParamValue ?: return
        synchronized(lock) {
            if (isFromActiveAutopilot(received)) accept(SystemId(received.systemId), message)
        }
    }

    /** [systemId] rebooted in [session] (#69): what it sent before may have changed, so all of it is forgotten. */
    fun rebooted(systemId: SystemId, session: SessionId) = synchronized(lock) {
        if (session == this.session) forgetHoldingLock(systemId)
    }

    /** Forgets what [systemId] sent in the current session, so that it is read again. */
    fun forget(systemId: SystemId) = synchronized(lock) { forgetHoldingLock(systemId) }

    private fun forgetHoldingLock(systemId: SystemId) {
        val followed = mutableParameters.value
        if (followed.systemId == systemId && followed.values.isNotEmpty()) {
            mutableParameters.value = followed.copy(values = emptyMap())
        }
    }

    /** Forgets every value and keeps only [session]'s from now on. */
    fun startSession(session: SessionId) = synchronized(lock) {
        this.session = session
        mutableParameters.value = FollowedParameters.empty(session)
    }

    // Under lock, as the two below.
    private fun isFromActiveAutopilot(received: ReceivedMessage): Boolean = received.session == session &&
        received.componentId == MAV_COMP_ID_AUTOPILOT1 &&
        SystemId(received.systemId) == activeVehicle.systemId.value

    private fun accept(systemId: SystemId, message: ParamValue) {
        val parameter = BatteryParameter.withId(message.paramId)
        val value = parameter?.takeIf { message.type == it.type }?.valueOf(message.value)
        when {
            !message.paramId.isAscii() -> malformedCount++
            parameter == null -> Unit
            value == null -> malformedCount++
            else -> apply(systemId, parameter, value)
        }
    }

    private fun apply(systemId: SystemId, parameter: BatteryParameter, value: Float) {
        val current = mutableParameters.value
        val followed = if (current.systemId == systemId) current else FollowedParameters(session, systemId, emptyMap())
        mutableParameters.value = followed.copy(values = followed.values + (parameter to value))
    }

    private fun String.isAscii(): Boolean = all { it.code <= MAX_ASCII }

    private companion object {
        const val MAV_COMP_ID_AUTOPILOT1 = 1
        const val MAX_ASCII = 0x7F
    }
}

/**
 * The parameters of [systemId], the active vehicle when they arrived, in [session]; published whole, so a consumer
 * never pairs one session's values with another's.
 */
internal data class FollowedParameters(
    val session: SessionId,
    val systemId: SystemId?,
    val values: Map<BatteryParameter, Float>,
) {
    companion object {
        fun empty(session: SessionId) = FollowedParameters(session, systemId = null, values = emptyMap())
    }
}
