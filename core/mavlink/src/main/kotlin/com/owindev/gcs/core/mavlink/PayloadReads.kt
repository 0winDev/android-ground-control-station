package com.owindev.gcs.core.mavlink

// Little-endian reads of payload fields for MavlinkDecoder, kept apart so the decoder only holds the message
// layouts. Every read goes through uint8, so the bytes of a field cut by truncation read as zero.

private const val UINT16_BYTES = 2
private const val INT32_BYTES = 4
private const val UINT32_BYTES = 4
private const val BYTE_MASK = 0xFF
private const val UINT32_MASK = 0xFFFF_FFFFL
private const val NUL: Byte = 0

/** A byte past the end of a truncated payload reads as zero, which restores it without copying. */
internal fun ByteArray.uint8(offset: Int): Int = if (offset < size) this[offset].toInt() and BYTE_MASK else 0

/** int8: the byte as a [Byte] extends the sign into the [Int]. */
internal fun ByteArray.int8(offset: Int): Int = uint8(offset).toByte().toInt()

internal fun ByteArray.uint16(offset: Int): Int = littleEndian(offset, UINT16_BYTES)

/** Little-endian int16: the 16 bits as a [Short] extend the sign into the [Int]. */
internal fun ByteArray.int16(offset: Int): Int = littleEndian(offset, UINT16_BYTES).toShort().toInt()

/** Little-endian int32: Kotlin's [Int] is the same two's-complement 32 bits, so the sign comes for free. */
internal fun ByteArray.int32(offset: Int): Int = littleEndian(offset, INT32_BYTES)

/** Little-endian uint32, returned as a [Long] so values above `Int.MAX_VALUE` stay positive. */
internal fun ByteArray.uint32(offset: Int): Long = littleEndian(offset, UINT32_BYTES).toLong() and UINT32_MASK

/**
 * Little-endian uint64 as a [Long]: the low uint32 first, then the high one. A value of 2⁶³ or more would come
 * out negative; MAVLink's uint64 fields are timestamps in µs, which stay below that for about 292,000 years.
 */
internal fun ByteArray.uint64(offset: Int): Long = (uint32(offset + UINT32_BYTES) shl Int.SIZE_BITS) or uint32(offset)

/** Little-endian IEEE-754 single-precision float: its 32 bits read as an int32, then reinterpreted. */
internal fun ByteArray.float(offset: Int): Float = Float.fromBits(int32(offset))

/**
 * Where the text of a `char[length]` field at [offset] ends: at the first NUL, the end of the field or the end of a
 * truncated payload, whichever comes first.
 */
internal fun ByteArray.textEnd(offset: Int, length: Int): Int {
    val fieldEnd = minOf(offset + length, size)
    var end = offset
    while (end < fieldEnd && this[end] != NUL) {
        end++
    }
    return end
}

/** The [bytes] bytes at [offset], least significant first, as the low bits of an [Int]. */
private fun ByteArray.littleEndian(offset: Int, bytes: Int): Int {
    var value = 0
    for (index in bytes - 1 downTo 0) {
        value = (value shl Byte.SIZE_BITS) or uint8(offset + index)
    }
    return value
}
