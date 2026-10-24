package com.owindev.gcs.core.domain.recording

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-038")
class ImportRuleTest {

    private lateinit var rule: ImportRule

    @BeforeEach
    fun setUp() {
        rule = ImportRule(maxBytes = MAX_BYTES)
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN the default rule WHEN reading its limit THEN is the recordings limit`() {
        val result = ImportRule().maxBytes

        result shouldBeEqualTo RecordingRule.DEFAULT_MAX_TOTAL_BYTES
    }

    @Test
    fun `GIVEN a limit of zero WHEN building the rule THEN it is rejected`() {
        val build = { ImportRule(maxBytes = 0) }

        val result = invoking(build)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a declared size over the limit WHEN checking it THEN the import is refused before copying`() {
        val declared = MAX_BYTES + 1

        val result = rule.refusesDeclared(declared)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a declared size exactly at the limit WHEN checking it THEN the import goes on`() {
        val declared = MAX_BYTES

        val result = rule.refusesDeclared(declared)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN no declared size WHEN checking it THEN the import goes on and is counted while copying`() {
        val declared: Long? = null

        val result = rule.refusesDeclared(declared)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a negative declared size WHEN checking it THEN it counts as unknown`() {
        val declared = -1L

        val result = rule.refusesDeclared(declared)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN one byte copied past the limit WHEN checking the count THEN the copy stops`() {
        val copied = MAX_BYTES + 1

        val result = rule.exceeds(copied)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN exactly the limit copied WHEN checking the count THEN the copy goes on`() {
        val copied = MAX_BYTES

        val result = rule.exceeds(copied)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a tlog name WHEN naming the import THEN keeps it`() {
        val displayName = "2026-10-21_18-04-11.tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "2026-10-21_18-04-11.tlog"
    }

    @Test
    fun `GIVEN an upper-case extension WHEN naming the import THEN ends in a lower-case tlog`() {
        val displayName = "flight.TLOG"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "flight.tlog"
    }

    @Test
    fun `GIVEN a name without the extension WHEN naming the import THEN adds it`() {
        val displayName = "flight.bin"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "flight.bin.tlog"
    }

    @Test
    fun `GIVEN spaces and accents WHEN naming the import THEN each becomes an underscore`() {
        val displayName = "vuelo de prueba ñ.tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "vuelo_de_prueba__.tlog"
    }

    @Test
    fun `GIVEN a path in the name WHEN naming the import THEN only the last part is kept`() {
        val displayName = "../../shared_prefs/settings.tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "settings.tlog"
    }

    @Test
    fun `GIVEN a backslash path in the name WHEN naming the import THEN only the last part is kept`() {
        val displayName = "..\\..\\settings.tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "settings.tlog"
    }

    @Test
    fun `GIVEN a hidden name WHEN naming the import THEN never starts with a dot`() {
        val displayName = ".last-closed.tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "last-closed.tlog"
    }

    @Test
    fun `GIVEN only dots WHEN naming the import THEN uses the default name`() {
        val displayName = "...tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "imported.tlog"
    }

    @Test
    fun `GIVEN no name WHEN naming the import THEN uses the default name`() {
        val displayName: String? = null

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "imported.tlog"
    }

    @Test
    fun `GIVEN a very long name WHEN naming the import THEN keeps its first 64 characters`() {
        val displayName = "a".repeat(300) + ".tlog"

        val result = rule.nameFor(displayName, taken = emptySet())

        result shouldBeEqualTo "a".repeat(64) + ".tlog"
    }

    @Test
    fun `GIVEN the name taken twice WHEN naming the import THEN takes the next free suffix`() {
        val taken = setOf("flight.tlog", "flight_1.tlog")

        val result = rule.nameFor("flight.tlog", taken)

        result shouldBeEqualTo "flight_2.tlog"
    }

    @Test
    fun `GIVEN the name taken in another case WHEN naming the import THEN counts as taken`() {
        val taken = setOf("FLIGHT.tlog")

        val result = rule.nameFor("flight.tlog", taken)

        result shouldBeEqualTo "flight_1.tlog"
    }

    @Test
    fun `GIVEN any name WHEN naming the import THEN is a single file name the recordings folder accepts`() {
        val displayNames = listOf(null, "", " ", "../x", "a/b\\c", ".", "..", "ñ", "\u0000.tlog", "-.tlog", "_")

        val result = displayNames.map { rule.nameFor(it, taken = emptySet()) }

        result.all { it.matches(RECORDING_NAME) }.shouldBeTrue()
    }

    private companion object {
        const val MAX_BYTES = 1_000L

        /** The recordings folder's own check (`LastClosedMarker.LOG_NAME` in `:data:vehicle`). */
        val RECORDING_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.tlog")
    }
}
