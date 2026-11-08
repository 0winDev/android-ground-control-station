package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects what the senders observe of the uplink (REQ-045): [GcsHeartbeatSender] reports its periods and resets it,
 * [com.owindev.gcs.data.vehicle.command.CommandExchange] reports commands without answer and answers.
 */
@Singleton
internal class DefaultUplinkRepository @Inject constructor(private val timeSource: TimeSource.WithComparableMarks) :
    UplinkRepository {

    private val mutableHealth = MutableStateFlow(UplinkHealth.HEALTHY)

    override val health: StateFlow<UplinkHealth> = mutableHealth.asStateFlow()

    fun heartbeatSent() = mutableHealth.update { it.afterHeartbeatSent() }

    fun heartbeatFailed() = mutableHealth.update { it.afterHeartbeatFailed() }

    fun commandUnanswered() = mutableHealth.update { it.afterCommandUnanswered(timeSource.markNow()) }

    /** Only for a COMMAND_ACK from the active vehicle's pinned route and system ID. */
    fun commandAnswered() = mutableHealth.update { it.afterCommandAnswered() }

    /** Whatever was observed belonged to a vehicle no longer CONNECTED, or to a session that ended. */
    fun reset() {
        mutableHealth.value = UplinkHealth.HEALTHY
    }
}
