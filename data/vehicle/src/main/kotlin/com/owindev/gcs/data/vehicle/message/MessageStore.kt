package com.owindev.gcs.data.vehicle.message

import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.HeartbeatMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The active vehicle's autopilot messages (REQ-047), newest first and at most [MAX_MESSAGES], and its latest `PreArm:`
 * reason, cleared when a HEARTBEAT shows it armed or SYS_STATUS reports its pre-arm checks healthy. Only that vehicle's
 * component 1, so a stream of made-up system IDs or components cannot grow it. The text is never logged.
 *
 * Written by the vehicle repository's listener, one message at a time, so it is filled for exactly as long as the link
 * is listened to. A chunked message waiting for its last chunk is closed by the vehicle's next message once its time
 * limit has passed. It holds one link session at a time, as the telemetry does (REQ-037): [startSession] forgets
 * everything, and a message from another session is dropped. The listener and the watchdog both call it, hence the lock.
 */
@Singleton
internal class MessageStore @Inject constructor(
    private val activeVehicle: ActiveVehicle,
    private val preArmRule: PreArmRule,
) {

    private val lock = Any()

    // Guarded by lock.
    private var session: SessionId = FIRST_SESSION
    private var assembler = StatusTextAssembler(FIRST_SESSION.origin)

    private val mutableMessages = MutableStateFlow(FollowedMessages.empty(FIRST_SESSION))

    val messages: StateFlow<FollowedMessages> = mutableMessages.asStateFlow()

    fun record(received: ReceivedMessage, receivedAt: TimeMark) = synchronized(lock) {
        if (received.session != session || received.componentId != MAV_COMP_ID_AUTOPILOT1) return@synchronized
        val followed = followedFor(SystemId(received.systemId)) ?: return@synchronized
        val completed = listOfNotNull(assembler.expired()) + completedBy(received.message, receivedAt)
        val reason = completed.lastOrNull(preArmRule::isPreArm)
            ?: followed.preArmReason.takeUnless { clearsPreArm(received) }
        val next = followed.copy(
            messages = (completed.asReversed() + followed.messages).take(MAX_MESSAGES),
            preArmReason = reason,
        )
        if (next != mutableMessages.value) mutableMessages.value = next
    }

    /** Forgets every message and keeps only [session]'s from now on. */
    fun startSession(session: SessionId) = synchronized(lock) {
        this.session = session
        assembler = StatusTextAssembler(session.origin)
        mutableMessages.value = FollowedMessages.empty(session)
    }

    /** What [systemId] adds to: `null` unless it is the active vehicle, fresh if it has just become so; under lock. */
    private fun followedFor(systemId: SystemId): FollowedMessages? {
        val followed = mutableMessages.value
        return when {
            systemId != activeVehicle.systemId.value -> null

            followed.systemId == systemId -> followed

            else -> {
                assembler = StatusTextAssembler(session.origin)
                FollowedMessages.empty(session).copy(systemId = systemId)
            }
        }
    }

    private fun completedBy(message: MavlinkMessage, receivedAt: TimeMark): List<VehicleMessage> =
        if (message is MavlinkMessage.StatusText) assembler.accept(message, receivedAt) else emptyList()

    private fun clearsPreArm(received: ReceivedMessage): Boolean = when (val message = received.message) {
        is MavlinkMessage.Heartbeat -> HeartbeatMapper.vehicleStateOf(received)?.armed == true

        is MavlinkMessage.SysStatus ->
            message.onboardControlSensorsEnabled and MAV_SYS_STATUS_PREARM_CHECK != 0L &&
                message.onboardControlSensorsHealth and MAV_SYS_STATUS_PREARM_CHECK != 0L

        else -> false
    }

    companion object {
        const val MAX_MESSAGES = 100

        private const val MAV_COMP_ID_AUTOPILOT1 = 1

        /** `common.xml`: "pre-arm check status. Always healthy when armed". */
        private const val MAV_SYS_STATUS_PREARM_CHECK = 0x10000000L
    }
}

/**
 * The messages of [systemId], the active vehicle when they arrived, in [session]; published whole, so a consumer never
 * pairs one session's messages with another's.
 */
internal data class FollowedMessages(
    val session: SessionId,
    val systemId: SystemId?,
    val messages: List<VehicleMessage>,
    val preArmReason: VehicleMessage?,
) {
    companion object {
        fun empty(session: SessionId) = FollowedMessages(session, systemId = null, emptyList(), preArmReason = null)
    }
}
