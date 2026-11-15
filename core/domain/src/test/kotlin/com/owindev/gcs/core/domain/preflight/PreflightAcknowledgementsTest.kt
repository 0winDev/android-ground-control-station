package com.owindev.gcs.core.domain.preflight

import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.preflight.PreflightColour.AMBER
import com.owindev.gcs.core.domain.preflight.PreflightColour.GREEN
import com.owindev.gcs.core.domain.preflight.PreflightColour.RED
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-048")
class PreflightAcknowledgementsTest {

    private lateinit var acknowledgements: PreflightAcknowledgements

    @BeforeEach
    fun setUp() {
        acknowledgements = PreflightAcknowledgements()
    }

    @Test
    fun `GIVEN an amber item not acknowledged WHEN judging THEN it needs acknowledgement`() {
        val check = checkOf(LIMITS_NOT_READ)

        val result = check.verdict(acknowledgements)

        result shouldBeEqualTo PreflightVerdict.NeedsAcknowledgement(listOf(LIMITS_NOT_READ))
    }

    @Test
    fun `GIVEN every amber item acknowledged WHEN judging THEN it is clear`() {
        val check = checkOf(LIMITS_NOT_READ, RESTRICTED)
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, check).acknowledge(RESTRICTED, check)

        val result = check.verdict(acknowledged)

        result shouldBeEqualTo PreflightVerdict.Clear
    }

    @Test
    fun `GIVEN one of two amber items acknowledged WHEN judging THEN only the other needs acknowledgement`() {
        val check = checkOf(LIMITS_NOT_READ, RESTRICTED)
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, check)

        val result = check.verdict(acknowledged)

        result shouldBeEqualTo PreflightVerdict.NeedsAcknowledgement(listOf(RESTRICTED))
    }

    @Test
    fun `GIVEN a red item and its amber items acknowledged WHEN judging THEN it is blocked by the red one`() {
        val check = checkOf(LIMITS_NOT_READ, NO_FIX)
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, check)

        val result = check.verdict(acknowledged)

        result shouldBeEqualTo PreflightVerdict.Blocked(listOf(NO_FIX))
    }

    @Test
    fun `GIVEN only green items WHEN judging THEN it is clear`() {
        val check = checkOf(GPS_OK)

        val result = check.verdict(acknowledgements)

        result shouldBeEqualTo PreflightVerdict.Clear
    }

    @Test
    fun `GIVEN a red item WHEN acknowledging it THEN nothing is acknowledged`() {
        val check = checkOf(NO_FIX)

        val result = acknowledgements.acknowledge(NO_FIX, check)

        result shouldBeEqualTo PreflightAcknowledgements()
    }

    @Test
    fun `GIVEN an amber item not in the check WHEN acknowledging it THEN nothing is acknowledged`() {
        val check = checkOf(GPS_OK)

        val result = acknowledgements.acknowledge(LIMITS_NOT_READ, check)

        result shouldBeEqualTo PreflightAcknowledgements()
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN its reason changes THEN the acknowledgement is dropped`() {
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, checkOf(LIMITS_NOT_READ))
        val changed = checkOf(WARN_ONLY)

        val result = changed.verdict(acknowledged.keptFor(changed))

        result shouldBeEqualTo PreflightVerdict.NeedsAcknowledgement(listOf(WARN_ONLY))
    }

    @Test
    fun `GIVEN an acknowledgement of the same kind for another reason WHEN judging THEN it does not count`() {
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, checkOf(LIMITS_NOT_READ))
        val changed = checkOf(WARN_ONLY)

        val result = changed.verdict(acknowledged)

        result shouldBeEqualTo PreflightVerdict.NeedsAcknowledgement(listOf(WARN_ONLY))
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN it turns red THEN the acknowledgement is dropped`() {
        val acknowledged = acknowledgements.acknowledge(RESTRICTED, checkOf(RESTRICTED))

        val result = acknowledged.keptFor(checkOf(BACKGROUND_NOT_RUNNING))

        result shouldBeEqualTo PreflightAcknowledgements()
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN it clears and comes back THEN it needs acknowledgement again`() {
        val acknowledged = acknowledgements.acknowledge(RESTRICTED, checkOf(RESTRICTED))
            .keptFor(checkOf(BACKGROUND_OK))
        val back = checkOf(RESTRICTED)

        val result = back.verdict(acknowledged.keptFor(back))

        result shouldBeEqualTo PreflightVerdict.NeedsAcknowledgement(listOf(RESTRICTED))
    }

    @Test
    fun `GIVEN an acknowledged amber item WHEN the next check holds it unchanged THEN it stays acknowledged`() {
        val acknowledged = acknowledgements.acknowledge(LIMITS_NOT_READ, checkOf(LIMITS_NOT_READ))
        val next = checkOf(LIMITS_NOT_READ, GPS_OK)

        val result = next.verdict(acknowledged.keptFor(next))

        result shouldBeEqualTo PreflightVerdict.Clear
    }
}

private fun checkOf(vararg items: PreflightItem) = PreflightCheck(items.toList())

private val LIMITS_NOT_READ = PreflightItem(PreflightKind.BATTERY_LIMITS, AMBER, PreflightReason.LimitsNotRead)

private val WARN_ONLY = PreflightItem(
    PreflightKind.BATTERY_LIMITS,
    AMBER,
    PreflightReason.FailsafeNotArmed(low = FailsafeStatus.ARMED, critical = FailsafeStatus.WARN_ONLY),
)

private val RESTRICTED = PreflightItem(PreflightKind.BACKGROUND_LINK, AMBER, PreflightReason.BackgroundRestricted)

private val BACKGROUND_NOT_RUNNING =
    PreflightItem(PreflightKind.BACKGROUND_LINK, RED, PreflightReason.BackgroundLinkNotRunning)

private val BACKGROUND_OK = PreflightItem(PreflightKind.BACKGROUND_LINK, GREEN, PreflightReason.Ok)

private val NO_FIX = PreflightItem(PreflightKind.GPS, RED, PreflightReason.NoFix3d)

private val GPS_OK = PreflightItem(PreflightKind.GPS, GREEN, PreflightReason.Ok)
