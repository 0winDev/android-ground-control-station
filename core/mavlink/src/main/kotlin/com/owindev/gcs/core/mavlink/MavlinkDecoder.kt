package com.owindev.gcs.core.mavlink

/** Turns valid frames into messages. Never throws: any frame gives a [MavlinkMessage]. */
object MavlinkDecoder {

    @Suppress("UnusedParameter")
    fun decode(frame: MavlinkFrame): MavlinkMessage = TODO()
}
