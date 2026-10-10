package com.owindev.gcs.core.mavlink

/**
 * Gives the CRC_EXTRA byte of each message the codec supports, as defined in the official message
 * definitions. The parser cannot validate a frame without it.
 */
fun interface CrcExtraLookup {

    /** Returns the CRC_EXTRA (0..255) of [msgId], or `null` if the message is not supported. */
    fun crcExtraFor(msgId: Int): Int?
}
