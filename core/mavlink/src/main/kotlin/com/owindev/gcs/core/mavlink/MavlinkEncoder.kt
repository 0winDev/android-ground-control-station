package com.owindev.gcs.core.mavlink

import com.owindev.gcs.core.mavlink.MavlinkMessage.Heartbeat

/** Turns messages into MAVLink 2 frames ready to send. Test-first stub: implemented in the next commit. */
object MavlinkEncoder {

    fun encode(sequence: Int, systemId: Int, componentId: Int, heartbeat: Heartbeat): ByteArray =
        TODO("Encode $heartbeat as seq $sequence from $systemId/$componentId")
}
