package com.owindev.gcs.feature.recordings

import androidx.annotation.StringRes

private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val TWO_DECIMALS_BELOW_MIB = 10.0
private const val ONE_DECIMAL_BELOW_MIB = 100.0
private const val SMALLEST_SHOWN_MIB = 0.01

/** How a size in MiB is written: fewer decimals as it grows, and a floor for a non-empty file. */
internal enum class SizeFormat(@StringRes val text: Int) {
    UNDER(R.string.recordings_size_under),
    TWO_DECIMALS(R.string.recordings_size_two_decimals),
    ONE_DECIMAL(R.string.recordings_size_one_decimal),
    WHOLE(R.string.recordings_size_whole),
}

internal data class MebibyteSize(val mebibytes: Double, val format: SizeFormat)

/**
 * [bytes] in MiB, the unit of the recordings limit (REQ-039), so a recording and the limit read in the same unit; a
 * non-empty file never shows as 0.
 */
internal fun mebibytesOf(bytes: Long): MebibyteSize {
    val mib = bytes / BYTES_PER_MIB
    return when {
        bytes > 0 && mib < SMALLEST_SHOWN_MIB -> MebibyteSize(SMALLEST_SHOWN_MIB, SizeFormat.UNDER)
        mib < TWO_DECIMALS_BELOW_MIB -> MebibyteSize(mib, SizeFormat.TWO_DECIMALS)
        mib < ONE_DECIMAL_BELOW_MIB -> MebibyteSize(mib, SizeFormat.ONE_DECIMAL)
        else -> MebibyteSize(mib, SizeFormat.WHOLE)
    }
}
