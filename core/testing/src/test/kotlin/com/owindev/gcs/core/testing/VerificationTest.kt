package com.owindev.gcs.core.testing

import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldNotThrow
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VerificationTest {

    private lateinit var beacon: Beacon

    @BeforeEach
    fun setUp() {
        beacon = mockk()
    }

    @Test
    fun `GIVEN a call made once WHEN verifyOnce THEN passes`() {
        every { beacon.ping() } just runs
        beacon.ping()

        val result = invoking { verifyOnce { beacon.ping() } }

        result shouldNotThrow AssertionError::class
    }

    @Test
    fun `GIVEN a call made twice WHEN verifyOnce THEN fails`() {
        every { beacon.ping() } just runs
        repeat(times = 2) { beacon.ping() }

        val result = invoking { verifyOnce { beacon.ping() } }

        result shouldThrow AssertionError::class
    }

    @Test
    fun `GIVEN no call made WHEN verifyNever THEN passes`() {
        every { beacon.ping() } just runs

        val result = invoking { verifyNever { beacon.ping() } }

        result shouldNotThrow AssertionError::class
    }

    @Test
    fun `GIVEN a call made WHEN verifyNever THEN fails`() {
        every { beacon.ping() } just runs
        beacon.ping()

        val result = invoking { verifyNever { beacon.ping() } }

        result shouldThrow AssertionError::class
    }

    private fun interface Beacon {
        fun ping()
    }
}
