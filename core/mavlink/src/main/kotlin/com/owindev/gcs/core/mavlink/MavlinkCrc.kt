package com.owindev.gcs.core.mavlink

/**
 * MAVLink checksum: X.25 (CRC-16/MCRF4XX) over the frame bytes after the start marker,
 * followed by the message's CRC_EXTRA.
 */
object MavlinkCrc {
    /**
     * Computes the checksum of [length] bytes of [bytes] starting at [offset], then [crcExtra].
     * Returns a value in 0..0xFFFF.
     *
     * @throws IllegalArgumentException if [offset] or [length] fall outside [bytes].
     */
    @Suppress("UnusedParameter")
    fun compute(bytes: ByteArray, offset: Int, length: Int, crcExtra: Int): Int = TODO()
}
