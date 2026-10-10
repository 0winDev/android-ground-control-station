package com.owindev.gcs.core.mavlink

/**
 * One valid MAVLink 2 frame: header fields, payload and optional signature.
 *
 * [payload] holds the LEN bytes exactly as received; MAVLink 2 senders may have truncated trailing
 * zeros, which decoding restores. [signature] is the 13-byte signature block when the frame carries
 * `MAVLINK_IFLAG_SIGNED`, otherwise `null`; it is not verified here (signing arrives in v0.6, and its
 * hash covers the flag bytes, so both are kept).
 *
 * Not a data class: it holds arrays, so equality compares their contents explicitly.
 */
// One property per field of the MAVLink 2 header on the wire; grouping them would hide that mapping.
@Suppress("LongParameterList")
class MavlinkFrame(
    val sequence: Int,
    val systemId: Int,
    val componentId: Int,
    val messageId: Int,
    val incompatFlags: Int,
    val compatFlags: Int,
    val payload: ByteArray,
    val signature: ByteArray?,
) {

    override fun equals(other: Any?): Boolean = other is MavlinkFrame &&
        sequence == other.sequence &&
        systemId == other.systemId &&
        componentId == other.componentId &&
        messageId == other.messageId &&
        incompatFlags == other.incompatFlags &&
        compatFlags == other.compatFlags &&
        payload.contentEquals(other.payload) &&
        signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = sequence
        result = HASH_MULTIPLIER * result + systemId
        result = HASH_MULTIPLIER * result + componentId
        result = HASH_MULTIPLIER * result + messageId
        result = HASH_MULTIPLIER * result + incompatFlags
        result = HASH_MULTIPLIER * result + compatFlags
        result = HASH_MULTIPLIER * result + payload.contentHashCode()
        result = HASH_MULTIPLIER * result + signature.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MavlinkFrame(seq=$sequence, sys=$systemId, comp=$componentId, msgid=$messageId, " +
            "incompat=$incompatFlags, compat=$compatFlags, len=${payload.size}, signed=${signature != null})"

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}
