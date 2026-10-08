package com.owindev.gcs.core.testing

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FixturesTest {

    private lateinit var fixtureReader: (String) -> ByteArray

    @BeforeEach
    fun setUp() {
        fixtureReader = ::readFixture
    }

    @Test
    fun `GIVEN a fixture on the test classpath WHEN readFixture THEN returns its exact bytes`() {
        val expected = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()).toList()

        val result = fixtureReader("fixtures/four-bytes.bin")

        result.toList() shouldBeEqualTo expected
    }

    @Test
    fun `GIVEN a missing fixture WHEN readFixture THEN fails naming the path`() {
        val missingPath = "fixtures/missing.bin"

        val result = invoking { fixtureReader(missingPath) }

        result shouldThrow IllegalStateException::class
    }
}
