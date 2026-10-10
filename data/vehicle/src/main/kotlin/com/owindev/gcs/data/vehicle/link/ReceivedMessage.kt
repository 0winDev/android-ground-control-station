package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.mavlink.MavlinkMessage

/**
 * A decoded message with the sender it came from. The codec's messages carry no header, so the system
 * and component IDs are taken from the frame here, where the data layer needs them to tell vehicles
 * (and their components) apart.
 */
internal data class ReceivedMessage(val systemId: Int, val componentId: Int, val message: MavlinkMessage)
