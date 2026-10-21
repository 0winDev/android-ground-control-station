package com.owindev.gcs.core.domain.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-037")
class ReplayRuleTest {

    private val clock = TestTimeSource()

    private lateinit var rule: ReplayRule

    @BeforeEach
    fun setUp() {
        rule = ReplayRule(LinkLossRule())
    }

    @Test
    fun `GIVEN no vehicle heard WHEN asking to replay THEN is allowed`() {
        val vehicles = emptyList<Vehicle>()

        val result = rule.refusalFor(vehicles)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a connected disarmed live vehicle WHEN asking to replay THEN is refused as connected`() {
        val activeVehicle = vehicle(link = VehicleLink.CONNECTED, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.refusalFor(listOf(activeVehicle))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_CONNECTED
    }

    @Test
    fun `GIVEN a connected armed live vehicle WHEN asking to replay THEN is refused as armed`() {
        val activeVehicle = vehicle(link = VehicleLink.CONNECTED, armed = true, heardAgo = HEARD_RECENTLY)

        val result = rule.refusalFor(listOf(activeVehicle))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_ARMED
    }

    @Test
    fun `GIVEN a lost live vehicle last heard armed WHEN asking to replay THEN is refused as armed`() {
        val activeVehicle = vehicle(link = VehicleLink.LOST, armed = true, heardAgo = LONG_SILENT)

        val result = rule.refusalFor(listOf(activeVehicle))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_ARMED
    }

    @Test
    fun `GIVEN a lost disarmed live vehicle WHEN asking to replay THEN is allowed`() {
        val activeVehicle = vehicle(link = VehicleLink.LOST, armed = false, heardAgo = LONG_SILENT)

        val result = rule.refusalFor(listOf(activeVehicle))

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a live vehicle the watchdog still calls connected past the timeout WHEN asking THEN is refused`() {
        val activeVehicle = vehicle(link = VehicleLink.CONNECTED, armed = false, heardAgo = LONG_SILENT)

        val result = rule.refusalFor(listOf(activeVehicle))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_CONNECTED
    }

    @Test
    fun `GIVEN a live vehicle lost but heard within the timeout WHEN asking to replay THEN is refused as connected`() {
        val activeVehicle = vehicle(link = VehicleLink.LOST, armed = false, heardAgo = HEARD_RECENTLY)

        val result = rule.refusalFor(listOf(activeVehicle))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_CONNECTED
    }

    @Test
    fun `GIVEN an armed vehicle from a replay WHEN asking to replay another THEN is allowed`() {
        val activeVehicle = vehicle(
            link = VehicleLink.CONNECTED,
            armed = true,
            heardAgo = HEARD_RECENTLY,
            origin = DataOrigin.REPLAY,
        )

        val result = rule.refusalFor(listOf(activeVehicle))

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN the active vehicle lost and another one armed and connected WHEN asking to replay THEN is refused`() {
        val active = vehicle(link = VehicleLink.LOST, armed = false, heardAgo = LONG_SILENT, systemId = 1)
        val other = vehicle(link = VehicleLink.CONNECTED, armed = true, heardAgo = Duration.ZERO, systemId = 2)

        val result = rule.refusalFor(listOf(active, other))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_ARMED
    }

    @Test
    fun `GIVEN the active vehicle lost and another one connected WHEN asking to replay THEN is refused as connected`() {
        val active = vehicle(link = VehicleLink.LOST, armed = false, heardAgo = LONG_SILENT, systemId = 1)
        val other = vehicle(link = VehicleLink.CONNECTED, armed = false, heardAgo = Duration.ZERO, systemId = 2)

        val result = rule.refusalFor(listOf(active, other))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_CONNECTED
    }

    @Test
    fun `GIVEN one vehicle connected and another armed WHEN asking to replay THEN armed is the reason`() {
        val connected = vehicle(link = VehicleLink.CONNECTED, armed = false, heardAgo = Duration.ZERO, systemId = 1)
        val armed = vehicle(link = VehicleLink.LOST, armed = true, heardAgo = Duration.ZERO, systemId = 2)

        val result = rule.refusalFor(listOf(connected, armed))

        result shouldBeEqualTo ReplayRefusal.VEHICLE_ARMED
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
