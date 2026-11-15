package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-028")
class SentVoltageReadTest {

    private lateinit var sentVoltage: SentVoltage.Companion

    @BeforeEach
    fun setUp() {
        sentVoltage = SentVoltage
    }

    @Test
    fun `GIVEN the resting voltage sent and the raw one compared WHEN reading THEN it may be above the one compared`() {
        val options = SEND_RESTING_VOLTAGE

        val result = sentVoltage.of(options = options, failsafeSource = RAW)

        result shouldBeEqualTo SentVoltage.MAY_BE_ABOVE_COMPARED
    }

    @Test
    fun `GIVEN the resting voltage sent and a source out of range WHEN reading THEN it may be above`() {
        val options = SEND_RESTING_VOLTAGE or OTHER_OPTION

        val result = sentVoltage.of(options = options, failsafeSource = SOURCE_OUT_OF_RANGE)

        result shouldBeEqualTo SentVoltage.MAY_BE_ABOVE_COMPARED
    }

    @Test
    fun `GIVEN the resting voltage sent and compared WHEN reading THEN it is never above the one compared`() {
        val options = SEND_RESTING_VOLTAGE

        val result = sentVoltage.of(options = options, failsafeSource = SAG_COMPENSATED)

        result shouldBeEqualTo SentVoltage.NEVER_ABOVE_COMPARED
    }

    @ParameterizedTest
    @ValueSource(ints = [RAW, SAG_COMPENSATED])
    fun `GIVEN the raw voltage sent WHEN reading THEN it is never above the one compared`(failsafeSource: Int) {
        val options = OTHER_OPTION

        val result = sentVoltage.of(options = options, failsafeSource = failsafeSource)

        result shouldBeEqualTo SentVoltage.NEVER_ABOVE_COMPARED
    }

    @Test
    fun `GIVEN the options not read WHEN reading THEN is unknown`() {
        val options: Int? = null

        val result = sentVoltage.of(options = options, failsafeSource = RAW)

        result shouldBeEqualTo SentVoltage.UNKNOWN
    }

    @Test
    fun `GIVEN the failsafe source not read WHEN reading THEN is unknown`() {
        val failsafeSource: Int? = null

        val result = sentVoltage.of(options = NO_OPTIONS, failsafeSource = failsafeSource)

        result shouldBeEqualTo SentVoltage.UNKNOWN
    }

    private companion object {
        const val NO_OPTIONS = 0

        /** Bit 6, "Send resistance compensated voltage to GCS" (`AP_BattMonitor_Params.cpp`). */
        const val SEND_RESTING_VOLTAGE = 64

        /** Bit 5, "MPPT Powered on at boot": one next to it, which must not count. */
        const val OTHER_OPTION = 32
        const val RAW = 0
        const val SAG_COMPENSATED = 1
        const val SOURCE_OUT_OF_RANGE = 2
    }
}
