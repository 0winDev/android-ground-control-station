package com.owindev.gcs.core.domain.background

import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The background link's warnings, from the states measured on the emulator and on ColorOS (#28). */
@Tag("REQ-046")
class BackgroundLinkWarningTest {

    private lateinit var rule: BackgroundLinkRule

    @BeforeEach
    fun setUp() {
        rule = BackgroundLinkRule(LinkLossRule(), RecordingRule())
    }

    @Test
    fun `GIVEN the stock defaults, optimised but not restricted WHEN running THEN only an advisory`() {
        val status = BackgroundLinkStatus(running = true, batteryOptimized = true)

        val result = rule.warningsFor(status)

        result shouldBeEqualTo setOf(BackgroundLinkWarning.BATTERY_OPTIMIZED)
    }

    @Test
    fun `GIVEN the app restricted by the operator WHEN running THEN a caution and no advisory for it`() {
        val status = BackgroundLinkStatus(running = true, batteryOptimized = true, backgroundRestricted = true)

        val result = rule.warningsFor(status)

        result shouldBeEqualTo setOf(BackgroundLinkWarning.BACKGROUND_RESTRICTED)
    }

    @Test
    fun `GIVEN the app exempt from optimisation WHEN running THEN no warning`() {
        val status = BackgroundLinkStatus(running = true)

        val result = rule.warningsFor(status)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN notifications hidden and exempt WHEN running THEN only the hidden notification`() {
        val status = BackgroundLinkStatus(running = true, notificationHidden = true)

        val result = rule.warningsFor(status)

        result shouldBeEqualTo setOf(BackgroundLinkWarning.NOTIFICATION_HIDDEN)
    }

    @Test
    fun `GIVEN every problem WHEN not running THEN no warning`() {
        val status = BackgroundLinkStatus(
            running = false,
            notificationHidden = true,
            batteryOptimized = true,
            backgroundRestricted = true,
        )

        val result = rule.warningsFor(status)

        result shouldBeEqualTo emptySet()
    }

    @Test
    fun `GIVEN a restriction WHEN deciding its level THEN it is a caution and the rest advisories`() {
        val warnings = BackgroundLinkWarning.entries

        val result = warnings.associateWith { it.level }

        result shouldBeEqualTo mapOf(
            BackgroundLinkWarning.NOTIFICATION_HIDDEN to AlertLevel.ADVISORY,
            BackgroundLinkWarning.BATTERY_OPTIMIZED to AlertLevel.ADVISORY,
            BackgroundLinkWarning.BACKGROUND_RESTRICTED to AlertLevel.CAUTION,
        )
    }
}
