package com.owindev.gcs.core.mavlink

/**
 * Extracts MAVLink 2 frames from a stream of datagrams.
 */
class MavlinkParser(
    @Suppress("UnusedPrivateProperty")
    private val crcExtraLookup: CrcExtraLookup,
) {

    /** What has been dropped so far. */
    val stats: ParserStats
        get() = TODO()

    /** Feeds one datagram and returns the valid frames it completes, in order. */
    @Suppress("UnusedParameter")
    fun parse(datagram: ByteArray): List<MavlinkFrame> = TODO()
}
