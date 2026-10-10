package com.owindev.gcs.core.mavlink

/**
 * MAVLink checksum: X.25 (CRC-16/MCRF4XX) over the frame bytes after the start marker,
 * followed by the message's CRC_EXTRA.
 *
 * Spec: https://mavlink.io/en/guide/serialization.html#checksum. Algorithm: `crc_accumulate`, seeded
 * with `X25_INIT_CRC` (`0xFFFF`), in https://github.com/mavlink/c_library_v2/blob/master/checksum.h.
 */
object MavlinkCrc {

    private const val SEED = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val CHECKSUM_MASK = 0xFFFF
    private const val NIBBLE_SHIFT = 4
    private const val BYTE_SHIFT = 8
    private const val TAP_SHIFT = 3

    /**
     * Computes the checksum of [length] bytes of [bytes] starting at [offset], then [crcExtra].
     * Returns a value in 0..0xFFFF. Only the low 8 bits of [crcExtra] are used.
     *
     * Allocation-free: it runs once per received frame.
     *
     * @throws IllegalArgumentException if [offset] or [length] fall outside [bytes]. That is a
     * caller bug, not bad network input: the parser checks frame bounds before calling this.
     */
    fun compute(bytes: ByteArray, offset: Int, length: Int, crcExtra: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Range offset=$offset length=$length is outside an array of ${bytes.size} bytes"
        }
        var crc = SEED
        for (index in offset until offset + length) {
            crc = accumulate(crc, bytes[index].toInt())
        }
        return accumulate(crc, crcExtra)
    }

    /** One step of `crc_accumulate` from the reference `checksum.h`. */
    private fun accumulate(crc: Int, byte: Int): Int {
        var tmp = (byte xor crc) and BYTE_MASK
        tmp = tmp xor ((tmp shl NIBBLE_SHIFT) and BYTE_MASK)
        val next = (crc ushr BYTE_SHIFT) xor (tmp shl BYTE_SHIFT) xor (tmp shl TAP_SHIFT) xor (tmp ushr NIBBLE_SHIFT)
        return next and CHECKSUM_MASK
    }
}
