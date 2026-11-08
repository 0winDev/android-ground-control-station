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
    fun `GIVEN a connected live vehicle WHEN asked whether it must run THEN it must`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = false)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a live vehicle the watchdog has not caught up with WHEN asked whether it must run THEN it must`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = false)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a live vehicle the watchdog still calls connected WHEN asked whether it must run THEN it must`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = LONG_SILENT)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = false)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a disarmed lost live vehicle WHEN asked whether it must run THEN it need not`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = LONG_SILENT)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = false)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a connected replayed vehicle WHEN asked whether it must run THEN it need not`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, origin = DataOrigin.REPLAY)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = false)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN no vehicle WHEN asked whether it must run THEN it need not`() {
        val active: Vehicle? = null

        val result = rule.mustRun(emptyList(), active, disconnectedByUser = false)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a connected disarmed vehicle disconnected by the operator WHEN asked whether it must run THEN not`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = true)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a vehicle disconnected by the operator WHEN it arms THEN it must run`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY)

        val result = rule.mustRun(listOf(active), active, disconnectedByUser = true)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a disconnected active vehicle and another live one armed WHEN asked whether it must run THEN it must`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)
        val other = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, systemId = 2)

        val result = rule.mustRun(listOf(active, other), active, disconnectedByUser = true)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a disarmed live vehicle WHEN the operator asks to stop THEN it may stop`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.operatorMayStop(listOf(active))

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN an armed live vehicle WHEN the operator asks to stop THEN it may not stop`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY)

        val result = rule.operatorMayStop(listOf(active))

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a lost live vehicle last heard armed WHEN the operator asks to stop THEN it may not stop`() {
        val active = vehicle(VehicleLink.LOST, armed = true, heardAgo = LONG_SILENT)

        val result = rule.operatorMayStop(listOf(active))

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed active vehicle and another live one armed WHEN the operator asks to stop THEN not`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)
        val other = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, systemId = 2)

        val result = rule.operatorMayStop(listOf(active, other))

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a connected live vehicle WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.endsFor(listOf(active), active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed vehicle lost just under 10 min WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = JUST_UNDER_LIMIT)

        val result = rule.endsFor(listOf(active), active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed vehicle lost for 10 min WHEN checking the end THEN it ends`() {
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = LIMIT)

        val result = rule.endsFor(listOf(active), active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a vehicle last heard armed lost for an hour WHEN checking the end THEN it goes on`() {
        val active = vehicle(VehicleLink.LOST, armed = true, heardAgo = AN_HOUR)

        val result = rule.endsFor(listOf(active), active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a disarmed vehicle lost for 10 min and another live one armed WHEN checking the end THEN it goes on`() {
        val other = vehicle(VehicleLink.LOST, armed = true, heardAgo = HEARD_RECENTLY, systemId = 2)
        val active = vehicle(VehicleLink.LOST, armed = false, heardAgo = LIMIT)

        val result = rule.endsFor(listOf(active, other), active)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a replayed vehicle WHEN checking the end THEN it ends`() {
        val active = vehicle(VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY, origin = DataOrigin.REPLAY)

        val result = rule.endsFor(listOf(active), active)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN no vehicle WHEN checking the end THEN it ends`() {
        val active: Vehicle? = null

        val result = rule.endsFor(emptyList(), active)

        result shouldBeEqualTo true
    }

    private fun vehicle(
        link: VehicleLink,
        armed: Boolean,
        heardAgo: Duration,
        origin: DataOrigin = DataOrigin.LIVE,
        systemId: Int = 1,
    ): Vehicle {
        val heardAt = clock.markNow()
        clock += heardAgo
        return Vehicle(
            state = VehicleState(SystemId(systemId), VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
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
