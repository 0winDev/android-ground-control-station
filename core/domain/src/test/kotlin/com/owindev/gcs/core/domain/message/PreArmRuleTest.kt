package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("REQ-047")
class PreArmRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: PreArmRule

    @BeforeEach
    fun setUp() {
        rule = PreArmRule()
    }

    @Test
    fun `GIVEN ArduPilot's pre-arm prefix WHEN checking THEN is a pre-arm reason`() {
        val message = message("PreArm: GPS 1: Bad fix")

        val result = rule.isPreArm(message)

        result shouldBeEqualTo true
    }

    @ParameterizedTest
    @ValueSource(strings = ["Arm: Throttle too high", "prearm: GPS 1: Bad fix", "PreArm:GPS", " PreArm: GPS", "PreArm"])
    fun `GIVEN a text without the exact prefix WHEN checking THEN is not a pre-arm reason`(text: String) {
        val message = message(text)

        val result = rule.isPreArm(message)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a reason just under 35 s old WHEN checking THEN is current`() {
        val reason = message(PRE_ARM_TEXT)
        clock += MAX_AGE - 1.milliseconds

        val result = rule.isCurrent(reason)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a reason exactly 35 s old WHEN checking THEN is not current`() {
        val reason = message(PRE_ARM_TEXT)
        clock += MAX_AGE

        val result = rule.isCurrent(reason)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a zero maximum age WHEN building the rule THEN it is refused`() {
        val maxAge = Duration.ZERO

        val result = invoking { PreArmRule(maxAge) }

        result shouldThrow IllegalArgumentException::class
    }

    private fun message(text: String) = VehicleMessage(
        severity = MessageSeverity.CRITICAL,
        text = text,
        receivedAt = clock.markNow(),
        origin = DataOrigin.LIVE,
    )

    private companion object {
        const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"
        val MAX_AGE = 35.seconds
    }
}
