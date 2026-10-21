package com.owindev.gcs.core.domain.vehicle

import com.owindev.gcs.core.domain.link.DataOrigin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-017")
class LinkLossRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: LinkLossRule

    @BeforeEach
    fun setUp() {
        rule = LinkLossRule()
    }

    @Test
    fun `GIVEN the default rule WHEN reading its timeout THEN is 3 seconds`() {
        val result = rule.timeout

        result shouldBeEqualTo 3.seconds
    }

    @Test
    fun `GIVEN just under 3 seconds since the last HEARTBEAT WHEN evaluating the link THEN is CONNECTED`() {
        val sinceLastHeartbeat = 3.seconds - 1.nanoseconds

        val result = rule.linkAfter(sinceLastHeartbeat)

        result shouldBeEqualTo VehicleLink.CONNECTED
    }

    @Test
    fun `GIVEN exactly 3 seconds since the last HEARTBEAT WHEN evaluating the link THEN is LOST`() {
        val sinceLastHeartbeat = 3.seconds

        val result = rule.linkAfter(sinceLastHeartbeat)

        result shouldBeEqualTo VehicleLink.LOST
    }

    @Test
    fun `GIVEN much more than 3 seconds since the last HEARTBEAT WHEN evaluating the link THEN is LOST`() {
        val sinceLastHeartbeat = 1_000.seconds

        val result = rule.linkAfter(sinceLastHeartbeat)

        result shouldBeEqualTo VehicleLink.LOST
    }

    @Test
    fun `GIVEN a HEARTBEAT just received WHEN evaluating the link THEN is CONNECTED`() {
        val sinceLastHeartbeat = Duration.ZERO

        val result = rule.linkAfter(sinceLastHeartbeat)

        result shouldBeEqualTo VehicleLink.CONNECTED
    }

    @Test
    fun `GIVEN a custom timeout WHEN reaching it THEN is LOST`() {
        val custom = LinkLossRule(timeout = CUSTOM_TIMEOUT)

        val result = custom.linkAfter(CUSTOM_TIMEOUT)

        result shouldBeEqualTo VehicleLink.LOST
    }

    @Test
    fun `GIVEN a custom timeout WHEN just under it THEN is CONNECTED`() {
        val custom = LinkLossRule(timeout = CUSTOM_TIMEOUT)

        val result = custom.linkAfter(CUSTOM_TIMEOUT - 1.nanoseconds)

        result shouldBeEqualTo VehicleLink.CONNECTED
    }

    @Test
    fun `GIVEN a vehicle the watchdog has not marked lost WHEN its age reaches the timeout THEN is lost`() {
        val vehicle = clock.vehicle(VehicleLink.CONNECTED)
        clock += 3.seconds

        val result = rule.isLost(vehicle)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a vehicle heard just now WHEN the watchdog has not marked it lost THEN is not lost`() {
        val vehicle = clock.vehicle(VehicleLink.CONNECTED)
        clock += 3.seconds - 1.nanoseconds

        val result = rule.isLost(vehicle)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a vehicle the watchdog marked lost WHEN its age is under the timeout THEN is lost`() {
        val vehicle = clock.vehicle(VehicleLink.LOST)

        val result = rule.isLost(vehicle)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a zero timeout WHEN creating the rule THEN is rejected`() {
        val creating = { LinkLossRule(timeout = Duration.ZERO) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `GIVEN a negative timeout WHEN creating the rule THEN is rejected`() {
        val creating = { LinkLossRule(timeout = (-1).seconds) }

        val result = invoking(creating)

        result shouldThrow IllegalArgumentException::class
    }
}

private val CUSTOM_TIMEOUT = 500.milliseconds

private fun TestTimeSource.vehicle(link: VehicleLink) = Vehicle(
    state = VehicleState(SystemId(1), VehicleType.COPTER, FlightMode.Named("Loiter"), armed = true),
    link = link,
    lastHeartbeat = markNow(),
    origin = DataOrigin.LIVE,
)
