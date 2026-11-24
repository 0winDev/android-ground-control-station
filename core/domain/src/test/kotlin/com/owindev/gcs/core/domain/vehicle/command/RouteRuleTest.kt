package com.owindev.gcs.core.domain.vehicle.command

import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-051")
class RouteRuleTest {

    private val clock = TestTimeSource()
    private val pinned = RouteAddress("192.168.1.10", 14550)
    private val other = RouteAddress("10.0.0.20", 14551)
    private val third = RouteAddress("10.0.0.30", 14552)
    private val timeout = LinkLossRule.DEFAULT_TIMEOUT

    private lateinit var rule: RouteRule

    @BeforeEach
    fun setUp() {
        rule = RouteRule(LinkLossRule())
    }

    @Test
    fun `GIVEN only the pinned address heard WHEN judging the route THEN is pinned`() {
        val route = routeOf(pinned to clock.markNow())

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Pinned
    }

    @Test
    fun `GIVEN no address heard WHEN judging the route THEN is pinned`() {
        val route = routeOf()

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Pinned
    }

    @Test
    fun `GIVEN another address heard 1 s after the pinned one WHEN judging the route THEN alternates`() {
        val pinnedAt = clock.markNow()
        clock += 1.seconds
        val route = routeOf(pinned to pinnedAt, other to clock.markNow())

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Alternating(pinned, listOf(other, pinned))
    }

    @Test
    fun `GIVEN another address heard just within a timeout of the pinned one WHEN judging THEN alternates`() {
        val pinnedAt = clock.markNow()
        clock += timeout - 1.nanoseconds
        val route = routeOf(pinned to pinnedAt, other to clock.markNow())

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Alternating(pinned, listOf(other, pinned))
    }

    @Test
    fun `GIVEN another address heard a timeout after the pinned one WHEN judging the route THEN changed to it`() {
        val pinnedAt = clock.markNow()
        clock += timeout
        val route = routeOf(pinned to pinnedAt, other to clock.markNow())

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Changed(from = pinned, to = other)
    }

    @Test
    fun `GIVEN the pinned address heard alone a timeout after another WHEN judging the route THEN is pinned`() {
        val otherAt = clock.markNow()
        clock += timeout
        val route = routeOf(other to otherAt, pinned to clock.markNow())

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Pinned
    }

    @Test
    fun `GIVEN three addresses heard within a timeout WHEN judging the route THEN alternates newest first`() {
        val pinnedAt = clock.markNow()
        clock += 1.seconds
        val otherAt = clock.markNow()
        clock += 1.seconds
        val route = routeOf(pinned to pinnedAt, third to clock.markNow(), other to otherAt)

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Alternating(pinned, listOf(third, other, pinned))
    }

    @Test
    fun `GIVEN an alternating route and nothing heard since WHEN time passes THEN still alternates`() {
        val pinnedAt = clock.markNow()
        clock += 1.seconds
        val route = routeOf(pinned to pinnedAt, other to clock.markNow())
        clock += 10.seconds

        val result = rule.statusOf(route)

        result shouldBeEqualTo RouteStatus.Alternating(pinned, listOf(other, pinned))
    }

    @Test
    fun `GIVEN an address heard a timeout before the newest WHEN listing the recent ones THEN is left out`() {
        val oldAt = clock.markNow()
        clock += timeout
        val heard = mapOf(third to oldAt, other to clock.markNow())

        val result = rule.recentOf(heard)

        result shouldBeEqualTo listOf(other)
    }

    @Test
    fun `GIVEN an IPv4 address WHEN displaying it THEN shows host and port`() {
        val address = RouteAddress("192.168.1.10", 14550)

        val result = address.display

        result shouldBeEqualTo "192.168.1.10:14550"
    }

    @Test
    fun `GIVEN an IPv6 address WHEN displaying it THEN shows the host in brackets`() {
        val address = RouteAddress("fe80::1", 14550)

        val result = address.display

        result shouldBeEqualTo "[fe80::1]:14550"
    }

    @Test
    fun `GIVEN an address WHEN turned into text THEN hides the host and the port`() {
        val address = RouteAddress("192.168.1.10", 14550)

        val result = "$address"

        result shouldNotContain "192.168.1.10"
        result shouldNotContain "14550"
    }

    private fun routeOf(vararg heard: Pair<RouteAddress, ComparableTimeMark>) =
        VehicleRoute(key = CommandKey(SystemId(1), connection = 1L), pinned = pinned, heard = mapOf(*heard))
}
