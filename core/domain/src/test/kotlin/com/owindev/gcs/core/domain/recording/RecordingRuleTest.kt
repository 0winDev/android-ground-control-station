package com.owindev.gcs.core.domain.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class RecordingRuleTest {

    private lateinit var rule: RecordingRule

    @BeforeEach
    fun setUp() {
        rule = RecordingRule()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the default rule WHEN reading its close timeout THEN is 10 minutes`() {
        val result = rule.closeAfterLinkLoss

        result shouldBeEqualTo 10.minutes
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN the default rule WHEN reading its limit THEN is 256 MiB`() {
        val result = rule.maxTotalBytes

        result shouldBeEqualTo 268_435_456L
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN exactly 10 minutes without a HEARTBEAT WHEN asking if the recording closes THEN it does`() {
        val sinceLastHeartbeat = 10.minutes

        val result = rule.closesAfter(sinceLastHeartbeat)

        result.shouldBeTrue()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN just under 10 minutes without a HEARTBEAT WHEN asking if the recording closes THEN it does not`() {
        val sinceLastHeartbeat = 10.minutes - 1.nanoseconds

        val result = rule.closesAfter(sinceLastHeartbeat)

        result.shouldBeFalse()
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN recordings within the limit WHEN choosing what to delete THEN nothing is deleted`() {
        val recordings = listOf(
            recording(OLDEST, MIB_100, 1),
            recording(MIDDLE, MIB_100, 2),
            recording(NEWEST, 56, 3),
        )

        val result = rule.toDelete(recordings, current = NEWEST)

        result.shouldBeEmpty()
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN recordings exactly at the limit WHEN choosing what to delete THEN nothing is deleted`() {
        val recordings = listOf(
            recording(OLDEST, MIB_100, 1),
            recording(MIDDLE, MIB_100, 2),
            recording(NEWEST, MIB_56, 3),
        )

        val result = rule.toDelete(recordings, current = null)

        result.shouldBeEmpty()
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN recordings one byte over the limit WHEN choosing what to delete THEN only the oldest goes`() {
        val oldest = recording(OLDEST, MIB_100, 1)
        val recordings = listOf(recording(NEWEST, MIB_56 + 1, 3), oldest, recording(MIDDLE, MIB_100, 2))

        val result = rule.toDelete(recordings, current = null)

        result shouldBeEqualTo listOf(oldest)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN recordings far over the limit WHEN choosing what to delete THEN the oldest go until the rest fit`() {
        val oldest = recording(OLDEST, MIB_100, 1)
        val middle = recording(MIDDLE, MIB_100, 2)
        val recordings = listOf(recording(NEWEST, MIB_200, 3), middle, oldest)

        val result = rule.toDelete(recordings, current = null)

        result shouldBeEqualTo listOf(oldest, middle)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN the oldest recording is the current one WHEN choosing what to delete THEN it is skipped`() {
        val middle = recording(MIDDLE, MIB_100, 2)
        val recordings = listOf(recording(OLDEST, MIB_100, 1), middle, recording(NEWEST, MIB_100, 3))

        val result = rule.toDelete(recordings, current = OLDEST)

        result shouldBeEqualTo listOf(middle)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a current recording over the limit by itself WHEN choosing what to delete THEN every other one goes`() {
        val oldest = recording(OLDEST, 1, 1)
        val middle = recording(MIDDLE, 1, 2)
        val recordings = listOf(oldest, middle, recording(NEWEST, MIB_300, 3))

        val result = rule.toDelete(recordings, current = NEWEST)

        result shouldBeEqualTo listOf(oldest, middle)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN two recordings written at the same instant WHEN choosing what to delete THEN the first by name goes`() {
        val first = recording(IMPORTED_A, MIB_200, 1)
        val recordings = listOf(recording(IMPORTED_B, MIB_200, 1), first)

        val result = rule.toDelete(recordings, current = null)

        result shouldBeEqualTo listOf(first)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN no recordings WHEN choosing what to delete THEN nothing is deleted`() {
        val recordings = emptyList<Recording>()

        val result = rule.toDelete(recordings, current = null)

        result.shouldBeEmpty()
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a custom limit WHEN over it THEN the oldest goes`() {
        val custom = RecordingRule(maxTotalBytes = 10)
        val oldest = recording(OLDEST, 6, 1)

        val result = custom.toDelete(listOf(oldest, recording(NEWEST, 6, 2)), current = NEWEST)

        result shouldBeEqualTo listOf(oldest)
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a zero close timeout WHEN creating the rule THEN is rejected`() {
        val creating = { RecordingRule(closeAfterLinkLoss = Duration.ZERO) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN a zero limit WHEN creating the rule THEN is rejected`() {
        val creating = { RecordingRule(maxTotalBytes = 0) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    private companion object {
        const val OLDEST = "2026-10-20_10-00-00.tlog"
        const val MIDDLE = "2026-10-20_11-00-00.tlog"
        const val NEWEST = "2026-10-20_12-00-00.tlog"
        const val IMPORTED_A = "a.tlog"
        const val IMPORTED_B = "b.tlog"
        const val MIB = 1024L * 1024
        const val MIB_56 = 56 * MIB
        const val MIB_100 = 100 * MIB
        const val MIB_200 = 200 * MIB
        const val MIB_300 = 300 * MIB

        fun recording(name: String, sizeBytes: Long, lastModifiedMillis: Long) =
            Recording(name = name, sizeBytes = sizeBytes, lastModifiedMillis = lastModifiedMillis)
    }
}
