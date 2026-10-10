package com.owindev.gcs.core.mavlink

/**
 * What the parser has dropped so far, by reason. Bad input is counted, never thrown (REQ-012).
 *
 * @property badChecksum frames whose checksum (with CRC_EXTRA) did not match.
 * @property unknownMessageId frames whose message ID has no CRC_EXTRA, skipped whole by their LEN.
 * @property unsupportedIncompatFlags frames with an incompatibility flag the codec does not support.
 * @property skippedBytes bytes discarded while searching for a start marker (noise, MAVLink 1 frames,
 * the start marker of a rejected frame).
 * @property emptyPayload frames with LEN 0, which a compliant sender never produces: "The first byte of
 * the payload is never truncated, even if the payload consists entirely of zeros"
 * (https://mavlink.io/en/guide/serialization.html, Empty-Byte Payload Truncation).
 */
data class ParserStats(
    val badChecksum: Long = 0,
    val unknownMessageId: Long = 0,
    val unsupportedIncompatFlags: Long = 0,
    val skippedBytes: Long = 0,
    val emptyPayload: Long = 0,
)
