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

@Tag("REQ-047")
class ShownMessageRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: ShownMessageRule

    @BeforeEach
    fun setUp() {
        rule = ShownMessageRule()
    }

    @Test
    fun `GIVEN nothing shown WHEN messages are kept THEN the newest is shown`() {
        val older = message(MessageSeverity.WARNING, "older")
        val newest = message(MessageSeverity.INFO, "newest")

        val result = rule.next(shown = null, messages = listOf(newest, older))

        result shouldBeEqualTo newest
    }

    @Test
    fun `GIVEN a message shown WHEN no message is kept any more THEN nothing is shown`() {
        val shown = message(MessageSeverity.CRITICAL, "shown")

        val result = rule.next(shown, messages = emptyList())

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a message shown WHEN it is no longer kept THEN the newest kept is shown`() {
        val shown = message(MessageSeverity.CRITICAL, "shown")
        val newest = message(MessageSeverity.INFO, "another vehicle's")

        val result = rule.next(shown, messages = listOf(newest))

        result shouldBeEqualTo newest
    }

    @Test
    fun `GIVEN a message shown WHEN nothing newer is kept THEN it stays`() {
        val shown = message(MessageSeverity.INFO, "shown")
        clock += SERIOUS_HOLD

        val result = rule.next(shown, messages = listOf(shown))

        result shouldBeEqualTo shown
    }

    @Test
    fun `GIVEN an advisory shown WHEN a newer advisory comes THEN it replaces it at once`() {
        val shown = message(MessageSeverity.INFO, "shown")
        val newer = message(MessageSeverity.NOTICE, "newer")

        val result = rule.next(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo newer
    }

    @Test
    fun `GIVEN a caution shown WHEN a newer emergency comes THEN it replaces it at once`() {
        val shown = message(MessageSeverity.WARNING, "shown")
        val newer = message(MessageSeverity.ERROR, "newer")

        val result = rule.next(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo newer
    }

    @Test
    fun `GIVEN a caution shown just under 5 s WHEN a newer advisory waits THEN the caution stays`() {
        val shown = message(MessageSeverity.WARNING, "shown")
        val newer = message(MessageSeverity.INFO, "newer")
        clock += HOLD - 1.milliseconds

        val result = rule.next(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo shown
    }

    @Test
    fun `GIVEN a caution shown for 5 s WHEN a newer advisory waits THEN the advisory replaces it`() {
        val shown = message(MessageSeverity.WARNING, "shown")
        val newer = message(MessageSeverity.INFO, "newer")
        clock += HOLD

        val result = rule.next(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo newer
    }

    @Test
    fun `GIVEN an emergency shown just under 30 s WHEN a burst of boot advisories waits THEN the emergency stays`() {
        val shown = message(MessageSeverity.CRITICAL, "shown")
        val burst = (1..BOOT_BURST).map { message(MessageSeverity.INFO, "boot $it") }.reversed()
        clock += SERIOUS_HOLD - 1.milliseconds

        val result = rule.next(shown, messages = burst + shown)

        result shouldBeEqualTo shown
    }

    @Test
    fun `GIVEN an emergency shown for 30 s WHEN a newer caution waits THEN the newest replaces it`() {
        val shown = message(MessageSeverity.CRITICAL, "shown")
        val caution = message(MessageSeverity.WARNING, "caution")
        val newest = message(MessageSeverity.INFO, "newest")
        clock += SERIOUS_HOLD

        val result = rule.next(shown, messages = listOf(newest, caution, shown))

        result shouldBeEqualTo newest
    }

    @Test
    fun `GIVEN a caution shown WHEN an emergency and a newer advisory come together THEN the emergency replaces it`() {
        val shown = message(MessageSeverity.WARNING, "shown")
        val emergency = message(MessageSeverity.ERROR, "emergency")
        val advisory = message(MessageSeverity.INFO, "advisory")

        val result = rule.next(shown, messages = listOf(advisory, emergency, shown))

        result shouldBeEqualTo emergency
    }

    @Test
    fun `GIVEN a caution shown for 2 s WHEN a newer advisory waits THEN it can change in 3 s`() {
        val shown = message(MessageSeverity.WARNING, "shown")
        val newer = message(MessageSeverity.INFO, "newer")
        clock += 2.seconds

        val result = rule.untilChange(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo 3.seconds
    }

    @Test
    fun `GIVEN an emergency shown for 2 s WHEN a newer advisory waits THEN it can change in 28 s`() {
        val shown = message(MessageSeverity.CRITICAL, "shown")
        val newer = message(MessageSeverity.INFO, "newer")
        clock += 2.seconds

        val result = rule.untilChange(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo 28.seconds
    }

    @Test
    fun `GIVEN a message shown WHEN nothing newer waits THEN it never changes by itself`() {
        val shown = message(MessageSeverity.WARNING, "shown")

        val result = rule.untilChange(shown, messages = listOf(shown))

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a message shown WHEN a newer one replaces it at once THEN nothing waits on its hold`() {
        val shown = message(MessageSeverity.INFO, "shown")
        val newer = message(MessageSeverity.WARNING, "newer")

        val result = rule.untilChange(shown, messages = listOf(newer, shown))

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a zero hold WHEN building the rule THEN it is refused`() {
        val hold = Duration.ZERO

        val result = invoking { ShownMessageRule(hold = hold) }

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a serious hold shorter than the hold WHEN building the rule THEN it is refused`() {
        val seriousHold = HOLD - 1.milliseconds

        val result = invoking { ShownMessageRule(hold = HOLD, seriousHold = seriousHold) }

        result shouldThrow IllegalArgumentException::class
    }

    private fun message(severity: MessageSeverity, text: String) = VehicleMessage(
        severity = severity,
        text = text,
        receivedAt = clock.markNow(),
        origin = DataOrigin.LIVE,
    )

    private companion object {
        const val BOOT_BURST = 23
        val HOLD = 5.seconds
        val SERIOUS_HOLD = 30.seconds
    }
}
