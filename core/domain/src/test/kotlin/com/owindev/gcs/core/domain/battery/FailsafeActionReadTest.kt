package com.owindev.gcs.core.domain.battery

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-028")
class FailsafeActionReadTest {

    private lateinit var action: FailsafeAction.Companion

    @BeforeEach
    fun setUp() {
        action = FailsafeAction
    }

    @Test
    fun `GIVEN an action code WHEN reading the action THEN acts with that code`() {
        val code = 2

        val result = action.of(code)

        result shouldBeEqualTo FailsafeAction.Acts(code)
    }

    @Test
    fun `GIVEN 0 WHEN reading the action THEN only warns`() {
        val code = 0

        val result = action.of(code)

        result shouldBeEqualTo FailsafeAction.WarnOnly
    }

    @Test
    fun `GIVEN nothing read WHEN reading the action THEN is unknown`() {
        val code: Int? = null

        val result = action.of(code)

        result shouldBeEqualTo FailsafeAction.Unknown
    }
}
