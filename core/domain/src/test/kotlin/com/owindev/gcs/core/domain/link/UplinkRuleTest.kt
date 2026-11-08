package com.owindev.gcs.core.domain.link

import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-045")
class UplinkRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: UplinkRule

    @BeforeEach
    fun setUp() {
        rule = UplinkRule()
    }

    @Test
    fun `GIVEN a healthy uplink WHEN evaluating THEN there is no warning`() {
        val health = UplinkHealth.HEALTHY

        val result = rule.warningOf(health)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN two failed periods in a row WHEN evaluating THEN there is no warning yet`() {
        val health = failedPeriods(2)

        val result = rule.warningOf(health)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN three failed periods in a row WHEN evaluating THEN sends are failing`() {
        val health = failedPeriods(3)

        val result = rule.warningOf(health)

        result shouldBeEqualTo UplinkWarning.SendsFailing
    }

    @Test
    fun `GIVEN a command without answer WHEN evaluating THEN the command is unanswered since then`() {
        val since = clock.markNow()
        val health = UplinkHealth.HEALTHY.afterCommandUnanswered(since)

        val result = rule.warningOf(health)

        result shouldBeEqualTo UplinkWarning.CommandUnanswered(since)
    }

    @Test
    fun `GIVEN failing sends and a command without answer WHEN evaluating THEN sends are failing`() {
        val health = failedPeriods(3).afterCommandUnanswered(clock.markNow())

        val result = rule.warningOf(health)

        result shouldBeEqualTo UplinkWarning.SendsFailing
    }

    @Test
    fun `GIVEN a run of failed periods WHEN a HEARTBEAT is sent THEN the run ends`() {
        val health = failedPeriods(3)

        val result = health.afterHeartbeatSent()

        result shouldBeEqualTo UplinkHealth.HEALTHY
    }

    @Test
    fun `GIVEN a command without answer WHEN a HEARTBEAT is sent THEN the command stays unanswered`() {
        val since = clock.markNow()
        val health = UplinkHealth.HEALTHY.afterCommandUnanswered(since)

        val result = health.afterHeartbeatSent()

        result shouldBeEqualTo UplinkHealth(unansweredSince = since)
    }

    @Test
    fun `GIVEN a command without answer and failed periods WHEN a command is answered THEN only the command clears`() {
        val health = failedPeriods(2).afterCommandUnanswered(clock.markNow())

        val result = health.afterCommandAnswered()

        result shouldBeEqualTo UplinkHealth(failedHeartbeatPeriods = 2)
    }

    @Test
    fun `GIVEN zero failed periods to warn WHEN building the rule THEN it is refused`() {
        val periods = 0

        val result = invoking { UplinkRule(periods) }

        result shouldThrow IllegalArgumentException::class
    }

    private fun failedPeriods(count: Int): UplinkHealth =
        (1..count).fold(UplinkHealth.HEALTHY) { health, _ -> health.afterHeartbeatFailed() }
}
