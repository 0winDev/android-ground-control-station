package com.owindev.gcs.core.domain.telemetry

import kotlin.time.TestTimeSource
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-026")
class TelemetryTest {

    private lateinit var telemetry: Telemetry

    @BeforeEach
    fun setUp() {
        telemetry = Telemetry(
            position = Timed(POSITION, RECEIVED_AT),
            attitude = Timed(ATTITUDE, RECEIVED_AT),
            airData = Timed(AIR_DATA, RECEIVED_AT),
            battery = Timed(BATTERY, RECEIVED_AT),
            gps = Timed(GPS, RECEIVED_AT),
        )
    }

    @Test
    fun `GIVEN every part received WHEN reading each kind THEN is its own part`() {
        val kinds = TelemetryKind.entries

        val result = kinds.associateWith { kind -> telemetry[kind]?.value }

        result shouldBeEqualTo mapOf(
            TelemetryKind.POSITION to POSITION,
            TelemetryKind.ATTITUDE to ATTITUDE,
            TelemetryKind.AIR_DATA to AIR_DATA,
            TelemetryKind.BATTERY to BATTERY,
            TelemetryKind.GPS to GPS,
        )
    }

    @Test
    fun `GIVEN nothing received WHEN reading each kind THEN there is no part`() {
        val empty = Telemetry()

        val result = TelemetryKind.entries.associateWith { kind -> empty[kind] }

        result shouldBeEqualTo TelemetryKind.entries.associateWith { null }
    }
}

private val RECEIVED_AT = TestTimeSource().markNow()

private val POSITION = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = -0.013,
    velocityNorth = 0.01,
    velocityEast = -0.01,
    velocityDown = 0.0,
    heading = 359.35,
)
private val ATTITUDE = Attitude(roll = 1.0, pitch = 2.0, yaw = 3.0)
private val AIR_DATA = AirData(airspeed = 1.0, groundspeed = 2.0, climbRate = 3.0, throttle = 4)
private val BATTERY = Battery(voltage = 12.6, current = 0.0, remaining = 100, monitored = true, healthy = true)
private val GPS = GpsStatus(
    fixType = GpsFixType.RtkFixed,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)
