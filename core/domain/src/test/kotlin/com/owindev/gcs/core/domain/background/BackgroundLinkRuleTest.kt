package com.owindev.gcs.core.domain.background

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-046")
class BackgroundLinkRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: BackgroundLinkRule

    @BeforeEach
    fun setUp() {
        rule = BackgroundLinkRule(LinkLossRule(), RecordingRule())
    }

    @Test
    fun `GIVEN a connected live vehicle WHEN asked to start THEN it starts`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.startsFor(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a live vehicle the watchdog has not caught up with WHEN asked to start THEN it starts`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.startsFor(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a live vehicle the watchdog still calls connected WHEN asked to start THEN it starts`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = LONG_SILENT)

        val result = rule.startsFor(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a lost live vehicle WHEN asked to start THEN it does not start`() {
        val active = vehicle(VehicleLink.LOST, armed = true, heardAgo = LONG_SILENT)

        val result = rule.startsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a connected replayed vehicle WHEN asked to start THEN it does not start`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, origin = DataOrigin.REPLAY)

        val result = rule.startsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN no vehicle WHEN asked to start THEN it does not start`() {
        val active: Vehicle? = null

        val result = rule.startsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed live vehicle WHEN the operator asks to stop THEN it may stop`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.operatorMayStop(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN an armed live vehicle WHEN the operator asks to stop THEN it may not stop`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY)

        val result = rule.operatorMayStop(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a lost live vehicle last heard armed WHEN the operator asks to stop THEN it may not stop`() {
        val active = vehicle(VehicleLink.LOST, armed = true, heardAgo = LONG_SILENT)

        val result = rule.operatorMayStop(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a connected live vehicle WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.endsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed vehicle lost just under 10 min WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = JUST_UNDER_LIMIT)

        val result = rule.endsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed vehicle lost for 10 min WHEN checking the end THEN it ends`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = LIMIT)

        val result = rule.endsFor(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a vehicle last heard armed lost for an hour WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.LOST, armed = true, heardAgo = AN_HOUR)

        val result = rule.endsFor(active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a replayed vehicle WHEN checking the end THEN it ends`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, origin = DataOrigin.REPLAY)

        val result = rule.endsFor(active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN no vehicle WHEN checking the end THEN it ends`() {
        val active: Vehicle? = null

        val result = rule.endsFor(active)

        result shouldBeEqualTo true
    }

    private fun vehicle(
        link: VehicleLink,
        armed: Boolean,
        heardAgo: Duration,
        origin: DataOrigin = DataOrigin.LIVE,
    ): Vehicle {
        val heardAt = clock.markNow()
        clock += heardAgo
        return Vehicle(
            state = VehicleState(SystemId(1), VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
            link = link,
            lastHeartbeat = heardAt,
            origin = origin,
        )
    }
}

private val HEARD_RECENTLY = 1.seconds
private val LONG_SILENT = 10.seconds
private val LIMIT = 10.minutes
private val JUST_UNDER_LIMIT = LIMIT - 1.milliseconds
private val AN_HOUR = 60.minutes
