package com.owindev.gcs.core.domain.attention

import com.owindev.gcs.core.domain.alert.ActiveAlert
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** What holds the operator on the flight screen (ADR 0007); the link-loss rule is the real one. */
@Tag("REQ-017")
@Tag("REQ-037")
class FlightAttentionRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: FlightAttentionRule

    @BeforeEach
    fun setUp() {
        rule = FlightAttentionRule(LinkLossRule())
    }

    @Test
    fun `GIVEN a live vehicle armed WHEN judging the attention THEN it is armed with no emergency`() {
        val vehicles = listOf(vehicle(armed = true))

        val result = rule.attentionOf(vehicles, Alerts.NotEvaluated)

        result shouldBeEqualTo FlightAttention(liveArmed = true, emergencies = emptySet())
    }

    @Test
    fun `GIVEN a live vehicle lost WHEN judging the attention THEN its link loss is an emergency`() {
        val vehicles = listOf(vehicle(armed = false, link = VehicleLink.LOST))

        val result = rule.attentionOf(vehicles, Alerts.NotEvaluated)

        result.emergencies shouldBeEqualTo setOf(Emergency.LinkLost(SYSTEM_1))
    }

    @Test
    fun `GIVEN live alerts WHEN judging the attention THEN only the emergencies count`() {
        val alerts = Alerts.Evaluated(
            listOf(alert(Alert.BatteryCritical, occurrence = 2), alert(Alert.GpsFixLost, occurrence = 1)),
        )

        val result = rule.attentionOf(emptyList(), alerts)

        result.emergencies shouldBeEqualTo setOf(Emergency.Raised(2))
    }

    @Test
    fun `GIVEN a replay's armed and lost vehicle and its emergency WHEN judging the attention THEN none counts`() {
        val vehicles = listOf(vehicle(armed = true, link = VehicleLink.LOST, origin = DataOrigin.REPLAY))
        val alerts = Alerts.Evaluated(listOf(alert(Alert.BatteryCritical, occurrence = 1, origin = DataOrigin.REPLAY)))

        val result = rule.attentionOf(vehicles, alerts)

        result shouldBeEqualTo FlightAttention(liveArmed = false, emergencies = emptySet())
    }

    @Test
    fun `GIVEN no live vehicle armed WHEN asking to leave the flight screen THEN the operator may`() {
        val attention = FlightAttention(liveArmed = false, emergencies = setOf(Emergency.LinkLost(SYSTEM_1)))

        val result = rule.mayLeave(attention)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a live vehicle armed WHEN asking to leave the flight screen THEN the operator may not`() {
        val attention = FlightAttention(liveArmed = true, emergencies = emptySet())

        val result = rule.mayLeave(attention)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a calm flight WHEN a live vehicle arms THEN the app returns to the flight screen`() {
        val before = FlightAttention(liveArmed = false, emergencies = emptySet())

        val result = rule.returnsToFlight(before, FlightAttention(liveArmed = true, emergencies = emptySet()))

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a calm flight WHEN a live link is lost THEN the app returns to the flight screen`() {
        val before = FlightAttention(liveArmed = false, emergencies = emptySet())

        val result = rule.returnsToFlight(before, LOST)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN a link loss already seen WHEN nothing new is raised THEN the operator stays where they are`() {
        val before = LOST

        val result = rule.returnsToFlight(before, LOST)

        result.shouldBeFalse()
    }

    @Test
    fun `GIVEN a link loss already seen WHEN a new emergency is raised THEN the app returns to the flight screen`() {
        val now = FlightAttention(liveArmed = false, emergencies = LOST.emergencies + Emergency.Raised(7))

        val result = rule.returnsToFlight(LOST, now)

        result.shouldBeTrue()
    }

    @Test
    fun `GIVEN an emergency WHEN it clears THEN the operator stays where they are`() {
        val now = FlightAttention(liveArmed = false, emergencies = emptySet())

        val result = rule.returnsToFlight(LOST, now)

        result.shouldBeFalse()
    }

    private fun vehicle(
        armed: Boolean,
        link: VehicleLink = VehicleLink.CONNECTED,
        origin: DataOrigin = DataOrigin.LIVE,
    ) = Vehicle(
        state = VehicleState(SYSTEM_1, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
        link = link,
        lastHeartbeat = clock.markNow(),
        origin = origin,
    )

    private fun alert(alert: Alert, occurrence: Long, origin: DataOrigin = DataOrigin.LIVE) = ActiveAlert(
        alert = alert,
        occurrence = occurrence,
        raisedAt = clock.markNow(),
        origin = origin,
    )

    private companion object {
        val SYSTEM_1 = SystemId(1)
        val LOST = FlightAttention(liveArmed = false, emergencies = setOf(Emergency.LinkLost(SYSTEM_1)))
    }
}
