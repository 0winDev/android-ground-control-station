package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.mavlink.MavlinkMessage
import java.net.InetSocketAddress

/**
 * A decoded message with the sender it came from. The codec's messages carry no header, so the system
 * and component IDs are taken from the frame here, where the data layer needs them to tell vehicles
 * (and their components) apart. [sender] is the UDP address the datagram came from: where replies to
 * that system go, and a replay's [REPLAY_SENDER][com.owindev.gcs.data.vehicle.replay.REPLAY_SENDER], where nothing
 * goes. [origin] says whether it came from the live link or a replay (REQ-037).
 */
internal data class ReceivedMessage(
    val systemId: Int,
    val componentId: Int,
    val message: MavlinkMessage,
    val sender: InetSocketAddress,
    val origin: DataOrigin,
)
