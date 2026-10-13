package com.owindev.gcs.core.mavlink

// Little-endian reads of payload fields for MavlinkDecoder, kept apart so the decoder only holds the message
// layouts. Every read goes through uint8, so the bytes of a field cut by truncation read as zero.

private const val UINT16_BYTES = 2
private const val INT32_BYTES = 4
private const val UINT32_BYTES = 4
private const val BYTE_MASK = 0xFF
private const val UINT32_MASK = 0xFFFF_FFFFL

/** A byte past the end of a truncated payload reads as zero, which restores it without copying. */
internal fun ByteArray.uint8(offset: Int): Int = if (offset < size) this[offset].toInt() and BYTE_MASK else 0

internal fun ByteArray.uint16(offset: Int): Int = littleEndian(offset, UINT16_BYTES)

/** Little-endian int32: Kotlin's [Int] is the same two's-complement 32 bits, so the sign comes for free. */
internal fun ByteArray.int32(offset: Int): Int = littleEndian(offset, INT32_BYTES)

/** Little-endian uint32, returned as a [Long] so values above `Int.MAX_VALUE` stay positive. */
internal fun ByteArray.uint32(offset: Int): Long = littleEndian(offset, UINT32_BYTES).toLong() and UINT32_MASK

/** The [bytes] bytes at [offset], least significant first, as the low bits of an [Int]. */
private fun ByteArray.littleEndian(offset: Int, bytes: Int): Int {
    var value = 0
    for (index in bytes - 1 downTo 0) {
        value = (value shl Byte.SIZE_BITS) or uint8(offset + index)
    }
    return value
}
