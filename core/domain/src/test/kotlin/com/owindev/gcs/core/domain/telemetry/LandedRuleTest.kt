package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@Tag("REQ-052")
class LandedRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: LandedRule

    @BeforeEach
    fun setUp() {
        rule = LandedRule()
    }

    @Test
    fun `GIVEN on the ground just under 0,5 s ago WHEN checking THEN is on the ground`() {
        val telemetry = landed(LandedState.ON_GROUND)
        clock += MAX_AGE - 1.milliseconds

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN on the ground exactly 0,5 s ago WHEN checking THEN is not on the ground`() {
        val telemetry = landed(LandedState.ON_GROUND)
        clock += MAX_AGE

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo false
    }

    @ParameterizedTest
    @EnumSource(LandedState::class, names = ["ON_GROUND"], mode = EnumSource.Mode.EXCLUDE)
    fun `GIVEN any fresh state but on the ground WHEN checking THEN is not on the ground`(state: LandedState) {
        val telemetry = landed(state)

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN telemetry without a landed state WHEN checking THEN is not on the ground`() {
        val telemetry = Telemetry(origin = DataOrigin.LIVE)

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN no telemetry at all WHEN checking THEN is not on the ground`() {
        val telemetry: Telemetry? = null

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo false
    }

    private fun landed(state: LandedState) = Telemetry(landed = Timed(state, clock.markNow()), origin = DataOrigin.LIVE)

    @Test
    fun `GIVEN a maximum age of zero WHEN building the rule THEN it is refused`() {
        val build = { LandedRule(maxAge = 0.milliseconds) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }

    private companion object {
        val MAX_AGE = 500.milliseconds
    }
}
