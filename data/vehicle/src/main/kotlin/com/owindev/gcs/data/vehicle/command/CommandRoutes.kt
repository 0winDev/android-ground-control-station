package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import java.net.InetSocketAddress

/** Where a command to a vehicle may go right now (REQ-043). */
internal fun interface CommandRoutes {
    fun routeTo(systemId: SystemId): CommandRoute
}

internal sealed interface CommandRoute {

    /** The address of the vehicle's first HEARTBEAT in this session, the only one commands go to. */
    data class Open(val address: InetSocketAddress) : CommandRoute

    data class Closed(val reason: NotSentReason) : CommandRoute
}
