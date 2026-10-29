package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
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
        rule = LandedRule(StalenessRule())
    }

    @Test
    fun `GIVEN on the ground just under 3 s ago WHEN checking THEN is on the ground`() {
        val telemetry = landed(LandedState.ON_GROUND)
        clock += LANDED_STALE_AFTER - 1.milliseconds

        val result = rule.isOnGround(telemetry)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN on the ground exactly 3 s ago WHEN checking THEN is not on the ground`() {
        val telemetry = landed(LandedState.ON_GROUND)
        clock += LANDED_STALE_AFTER

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

    private companion object {
        val LANDED_STALE_AFTER = 3.seconds
    }
}
