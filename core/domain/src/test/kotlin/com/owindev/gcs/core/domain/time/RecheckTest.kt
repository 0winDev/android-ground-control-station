package com.owindev.gcs.core.domain.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-037")
class RecheckTest {

    @Test
    fun `GIVEN a wait shorter than the minimum WHEN bounding it THEN waits the minimum`() {
        val wait = 1.milliseconds

        val result = wait.atLeastRecheck()

        result shouldBeEqualTo MIN_RECHECK
    }

    @Test
    fun `GIVEN no wait at all WHEN bounding it THEN waits the minimum`() {
        val wait = Duration.ZERO

        val result = wait.atLeastRecheck()

        result shouldBeEqualTo MIN_RECHECK
    }

    @Test
    fun `GIVEN a wait longer than the minimum WHEN bounding it THEN keeps it`() {
        val wait = 3.seconds

        val result = wait.atLeastRecheck()

        result shouldBeEqualTo wait
    }

    @Test
    fun `GIVEN the device's clock WHEN time passes THEN a mark on the device clock ages with it`() {
        val device = TestTimeSource()
        val mark = DeviceClock(device).markNow()
        device += 3.seconds

        val result = mark.elapsedNow()

        result shouldBeEqualTo 3.seconds
    }
}
