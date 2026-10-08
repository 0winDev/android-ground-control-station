package com.owindev.gcs

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Smoke test: JUnit 5, coroutines-test, Turbine and Kluent run in this Android module. */
class TestStackSmokeTest {

    private lateinit var flow: Flow<Int>

    @BeforeEach
    fun setUp() {
        flow = flowOf(EMITTED)
    }

    @Test
    fun `GIVEN a single-value flow WHEN collecting it with Turbine THEN receives the value`() = runTest {
        turbineScope {
            val expected = EMITTED

            val result = flow.testIn(backgroundScope)

            result.awaitItem() shouldBeEqualTo expected
            result.awaitComplete()
        }
    }

    private companion object {
        const val EMITTED = 1
    }
}
