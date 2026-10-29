package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-026")
class StalenessRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: StalenessRule

    @BeforeEach
    fun setUp() {
        rule = StalenessRule()
    }

    @Test
    fun `GIVEN the default rule WHEN reading its thresholds THEN are the ones decided in #39`() {
        val kinds = TelemetryKind.entries

        val result = kinds.associateWith(rule::thresholdOf)

        result shouldBeEqualTo mapOf(
            TelemetryKind.POSITION to 1.seconds,
            TelemetryKind.ATTITUDE to 500.milliseconds,
            TelemetryKind.AIR_DATA to 1.seconds,
            TelemetryKind.BATTERY to 3.seconds,
            TelemetryKind.GPS to 2.seconds,
            TelemetryKind.LANDED to 1.seconds,
        )
    }

    @Test
    fun `GIVEN a value just received WHEN judging it THEN is not stale`() {
        val age = Duration.ZERO

        val result = rule.isStale(TelemetryKind.ATTITUDE, age)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a value just under its threshold WHEN judging it THEN is not stale`() {
        val age = 500.milliseconds - 1.nanoseconds

        val result = rule.isStale(TelemetryKind.ATTITUDE, age)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a value exactly at its threshold WHEN judging it THEN is stale`() {
        val age = 500.milliseconds

        val result = rule.isStale(TelemetryKind.ATTITUDE, age)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN an age stale for one kind WHEN judging another kind with a longer threshold THEN is not stale`() {
        val age = 2.seconds

        val result = rule.isStale(TelemetryKind.BATTERY, age)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN custom thresholds WHEN reaching one THEN is stale`() {
        val custom = StalenessRule(thresholds = customThresholds())

        val result = custom.isStale(TelemetryKind.GPS, CUSTOM_THRESHOLD)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN custom thresholds WHEN just under one THEN is not stale`() {
        val custom = StalenessRule(thresholds = customThresholds())

        val result = custom.isStale(TelemetryKind.GPS, CUSTOM_THRESHOLD - 1.nanoseconds)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN thresholds missing a kind WHEN creating the rule THEN is rejected`() {
        val creating = { StalenessRule(thresholds = customThresholds() - TelemetryKind.BATTERY) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a zero threshold WHEN creating the rule THEN is rejected`() {
        val creating = { StalenessRule(thresholds = customThresholds() + (TelemetryKind.GPS to Duration.ZERO)) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a negative threshold WHEN creating the rule THEN is rejected`() {
        val creating = { StalenessRule(thresholds = customThresholds() + (TelemetryKind.GPS to (-1).seconds)) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN values received at different times WHEN asking when to judge again THEN is the next to go stale`() {
        val battery = Timed(BATTERY, clock.markNow())
        clock += 400.milliseconds
        val attitude = Timed(LEVEL, clock.markNow())
        clock += 300.milliseconds

        val result = rule.untilNextStale(Telemetry(attitude = attitude, battery = battery, origin = DataOrigin.LIVE))

        result shouldBeEqualTo 200.milliseconds
    }

    @Test
    fun `GIVEN only stale or missing telemetry WHEN asking when to judge again THEN there is no need to`() {
        val attitude = Timed(LEVEL, clock.markNow())
        clock += rule.thresholdOf(TelemetryKind.ATTITUDE)

        val result = rule.untilNextStale(Telemetry(attitude = attitude, origin = DataOrigin.LIVE))

        result.shouldBeNull()
    }
}

private val CUSTOM_THRESHOLD = 250.milliseconds

private fun customThresholds(): Map<TelemetryKind, Duration> = TelemetryKind.entries.associateWith { CUSTOM_THRESHOLD }

private val LEVEL = Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0)

private val BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)
