package com.owindev.gcs.core.mavlink

/**
 * The messages the codec supports in v0.1: CRC_EXTRA (to validate frames) and full payload length,
 * extension fields included (to zero-fill truncated payloads).
 */
object MavlinkMessages : CrcExtraLookup {

    override fun crcExtraFor(msgId: Int): Int? = TODO()

    /** Full payload length of [msgId] in bytes, extension fields included, or `null` if not supported. */
    @Suppress("UnusedParameter")
    fun fullLengthOf(msgId: Int): Int? = TODO()
}
