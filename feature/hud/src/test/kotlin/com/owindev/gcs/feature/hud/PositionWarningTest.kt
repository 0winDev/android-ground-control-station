package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.geo.Mgrs
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.PositionTrust
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-029")
class PositionWarningTest {

    @Test
    fun `GIVEN a verified position WHEN warning about it THEN there is nothing to say`() {
        val position = position(PositionTrust.VERIFIED)

        val result = positionWarningOf(position, gps = FRESH_RTK, lostFor = null)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN no GPS report WHEN warning about the position THEN says there is none`() {
        val position = position(PositionTrust.NO_GPS)

        val result = positionWarningOf(position, gps = null, lostFor = null)

        result shouldBeEqualTo PositionWarning.NoGps
    }

    @Tag("REQ-026")
    @Test
    fun `GIVEN a GPS that stopped reporting WHEN warning about the position THEN says so with the GPS age`() {
        val position = position(PositionTrust.GPS_STALE)

        val result = positionWarningOf(position, gps = STALE_RTK, lostFor = null)

        result shouldBeEqualTo PositionWarning.GpsStale(age = GPS_AGE)
    }

    @Test
    fun `GIVEN a GPS without a 3D fix WHEN warning about the position THEN says the fix is missing`() {
        val position = position(PositionTrust.NO_3D_FIX)

        val result = positionWarningOf(position, gps = FRESH_RTK, lostFor = null)

        result shouldBeEqualTo PositionWarning.No3dFix
    }

    @Test
    fun `GIVEN ArduPilot's 0°, 0° WHEN warning about the position THEN says it has no position yet`() {
        val position = position(PositionTrust.UNINITIALISED)

        val result = positionWarningOf(position, gps = FRESH_RTK, lostFor = null)

        result shouldBeEqualTo PositionWarning.Uninitialised
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the link lost and a stale RTK fix WHEN warning about the position THEN says it is the last data`() {
        val position = position(PositionTrust.GPS_STALE)

        val result = positionWarningOf(position, gps = STALE_RTK, lostFor = LOST_FOR)

        result shouldBeEqualTo PositionWarning.LinkLost(age = LOST_FOR)
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the link lost and a position verified at the loss WHEN warning THEN says it is the last data`() {
        val position = position(PositionTrust.VERIFIED)

        val result = positionWarningOf(position, gps = FRESH_RTK, lostFor = LOST_FOR)

        result shouldBeEqualTo PositionWarning.LinkLost(age = LOST_FOR)
    }

    @Test
    fun `GIVEN no position WHEN warning about it THEN there is nothing to say`() {
        val position: Reading<PositionReading>? = null

        val result = positionWarningOf(position, gps = FRESH_RTK, lostFor = LOST_FOR)

        result.shouldBeNull()
    }
}

private val GPS_AGE = 3.seconds
private val LOST_FOR = 54.seconds

private val RTK_FIXED = GpsStatus(
    fixType = GpsFixType.RtkFixed,
    satellitesVisible = 10,
    hdop = 0.7,
    vdop = 1.2,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.02,
    verticalAccuracy = 0.03,
    yaw = null,
)
private val FRESH_RTK = Reading(RTK_FIXED, stale = false, age = Duration.ZERO)
private val STALE_RTK = Reading(RTK_FIXED, stale = true, age = GPS_AGE)

private val SITL_HOME = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 584.09,
    altitudeAboveHome = 0.0,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = 359.0,
)

private fun position(trust: PositionTrust) =
    Reading(PositionReading(SITL_HOME, Mgrs.NotAvailable, trust), stale = false, age = Duration.ZERO)
