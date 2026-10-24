package com.owindev.gcs.feature.recordings

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-038")
class SizeTextTest {

    @Test
    fun `GIVEN the recordings limit WHEN sized THEN 256 MiB, whole`() {
        val bytes = 256L * MIB

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(256.0, SizeFormat.WHOLE)
    }

    @Test
    fun `GIVEN a file just over the limit WHEN sized THEN in the limit's unit`() {
        val bytes = 257L * MIB

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(257.0, SizeFormat.WHOLE)
    }

    @Test
    fun `GIVEN a flight log of some kilobytes WHEN sized THEN two decimals of a MiB`() {
        val bytes = MIB / 4

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(0.25, SizeFormat.TWO_DECIMALS)
    }

    @Test
    fun `GIVEN tens of MiB WHEN sized THEN one decimal`() {
        val bytes = 12L * MIB + MIB / 2

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(12.5, SizeFormat.ONE_DECIMAL)
    }

    @Test
    fun `GIVEN a file under a hundredth of a MiB WHEN sized THEN never 0`() {
        val bytes = 1_000L

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(0.01, SizeFormat.UNDER)
    }

    @Test
    fun `GIVEN an empty file WHEN sized THEN 0 with two decimals`() {
        val bytes = 0L

        val result = mebibytesOf(bytes)

        result shouldBeEqualTo MebibyteSize(0.0, SizeFormat.TWO_DECIMALS)
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
