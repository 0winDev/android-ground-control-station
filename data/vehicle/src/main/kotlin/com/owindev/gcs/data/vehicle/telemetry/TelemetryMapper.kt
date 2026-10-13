package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.GlobalPositionInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.GpsRawInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.SysStatus
import com.owindev.gcs.core.mavlink.MavlinkMessage.VfrHud
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import kotlin.time.TimeMark

/**
 * Maps the telemetry messages to the domain: SI units except angles, which are in degrees, and every
 * "unknown" value to `null`, never to 0.
 *
 * Only the autopilot describes the vehicle, as for HEARTBEATs: other components of the same system must not
 * overwrite its telemetry. Units and special values from `common.xml` and `standard.xml`
 * (https://mavlink.io/en/messages/common.html), as quoted on each message in `:core:mavlink`.
 */
internal object TelemetryMapper {

    private const val MAV_COMP_ID_AUTOPILOT1 = 1

    private const val DEGE7_PER_DEGREE = 1e7
    private const val CENTI = 100.0
    private const val MILLI = 1_000.0
    private const val PERCENT_MAX = 100
    private const val NORTH_DEGREES = 0.0

    /** GLOBAL_POSITION_INT `hdg`: "If unknown, set to: UINT16_MAX". */
    private const val GLOBAL_POSITION_HDG_UNKNOWN = 0xFFFF

    // GPS_FIX_TYPE (common.xml).
    private const val GPS_FIX_TYPE_NO_GPS = 0
    private const val GPS_FIX_TYPE_NO_FIX = 1
    private const val GPS_FIX_TYPE_2D_FIX = 2
    private const val GPS_FIX_TYPE_3D_FIX = 3
    private const val GPS_FIX_TYPE_DGPS = 4
    private const val GPS_FIX_TYPE_RTK_FLOAT = 5
    private const val GPS_FIX_TYPE_RTK_FIXED = 6
    private const val GPS_FIX_TYPE_STATIC = 7
    private const val GPS_FIX_TYPE_PPP = 8

    /**
     * ArduPilot sends `voltage_battery` 0, not the UINT16_MAX the definition gives, when it has no battery
     * monitor: `AP_BattMonitor::gcs_voltage` returns 0 without a driver (https://github.com/ArduPilot/ardupilot,
     * `libraries/AP_BattMonitor/AP_BattMonitor.cpp` at commit 7af20c9f), as the real boot frame in
     * `truncated/sys_status.bin` shows. A powered autopilot never reads 0 V on its own battery, so 0 means no
     * reading (ADR 0003: autopilot-specific, kept here).
     */
    private const val ARDUPILOT_NO_BATTERY_MONITOR_MV = 0

    /**
     * Extension fields a sender omits or truncates read as 0 (#35, #38), so a 0 accuracy is no reading, not a
     * perfect one.
     */
    private const val ACCURACY_ZERO_FILLED = 0L

    /**
     * [telemetry] with the part [received] carries, marked [receivedAt], or `null` if [received] is not
     * telemetry from a vehicle's autopilot.
     */
    fun update(telemetry: Telemetry, received: ReceivedMessage, receivedAt: TimeMark): Telemetry? {
        if (received.componentId != MAV_COMP_ID_AUTOPILOT1) return null
        return when (val message = received.message) {
            is GlobalPositionInt -> telemetry.copy(position = Timed(message.toPosition(), receivedAt))
            is MavlinkMessage.Attitude -> telemetry.copy(attitude = Timed(message.toAttitude(), receivedAt))
            is VfrHud -> telemetry.copy(airData = Timed(message.toAirData(), receivedAt))
            is SysStatus -> telemetry.copy(battery = Timed(message.toBattery(), receivedAt))
            is GpsRawInt -> telemetry.copy(gps = Timed(message.toGpsStatus(), receivedAt))
            else -> null
        }
    }

    private fun GlobalPositionInt.toPosition() = GlobalPosition(
        latitude = lat / DEGE7_PER_DEGREE,
        longitude = lon / DEGE7_PER_DEGREE,
        altitudeMsl = alt / MILLI,
        altitudeAboveHome = relativeAlt / MILLI,
        velocityNorth = vx / CENTI,
        velocityEast = vy / CENTI,
        velocityDown = vz / CENTI,
        heading = hdg.takeUnless { it == GLOBAL_POSITION_HDG_UNKNOWN }?.let { it / CENTI },
    )

    private fun MavlinkMessage.Attitude.toAttitude() = Attitude(
        roll = Math.toDegrees(roll.toDouble()),
        pitch = Math.toDegrees(pitch.toDouble()),
        yaw = Math.toDegrees(yaw.toDouble()),
    )

    private fun VfrHud.toAirData() = AirData(
        airspeed = airspeed.toDouble(),
        groundspeed = groundspeed.toDouble(),
        climbRate = climb.toDouble(),
        throttle = throttle,
    )

    private fun SysStatus.toBattery() = Battery(
        voltage = voltageBattery
            .takeUnless { it == SysStatus.VOLTAGE_BATTERY_UNKNOWN || it == ARDUPILOT_NO_BATTERY_MONITOR_MV }
            ?.let { it / MILLI },
        current = currentBattery.takeUnless { it == SysStatus.CURRENT_BATTERY_UNKNOWN }?.let { it / CENTI },
        // -1 is the documented "not sent"; any other value outside 0–100 is no percentage either.
        remaining = batteryRemaining.takeIf { it in 0..PERCENT_MAX },
    )

    private fun GpsRawInt.toGpsStatus() = GpsStatus(
        fixType = gpsFixTypeOf(fixType),
        satellitesVisible = satellitesVisible.takeUnless { it == GpsRawInt.SATELLITES_VISIBLE_UNKNOWN },
        hdop = eph.takeUnless { it == GpsRawInt.EPH_UNKNOWN }?.let { it / CENTI },
        vdop = epv.takeUnless { it == GpsRawInt.EPV_UNKNOWN }?.let { it / CENTI },
        groundSpeed = vel.takeUnless { it == GpsRawInt.VEL_UNKNOWN }?.let { it / CENTI },
        courseOverGround = cog.takeUnless { it == GpsRawInt.COG_UNKNOWN }?.let { it / CENTI },
        horizontalAccuracy = hAcc.takeUnless { it == ACCURACY_ZERO_FILLED }?.let { it / MILLI },
        verticalAccuracy = vAcc.takeUnless { it == ACCURACY_ZERO_FILLED }?.let { it / MILLI },
        yaw = gpsYawOf(yaw),
    )

    /** 0 is "not provided" and UINT16_MAX "unable to provide it", so north is sent as 36000. */
    private fun gpsYawOf(yaw: Int): Double? = when (yaw) {
        GpsRawInt.YAW_NOT_PROVIDED, GpsRawInt.YAW_UNAVAILABLE -> null
        GpsRawInt.YAW_NORTH -> NORTH_DEGREES
        else -> yaw / CENTI
    }

    private fun gpsFixTypeOf(fixType: Int): GpsFixType = when (fixType) {
        GPS_FIX_TYPE_NO_GPS -> GpsFixType.NoGps
        GPS_FIX_TYPE_NO_FIX -> GpsFixType.NoFix
        GPS_FIX_TYPE_2D_FIX -> GpsFixType.Fix2d
        GPS_FIX_TYPE_3D_FIX -> GpsFixType.Fix3d
        GPS_FIX_TYPE_DGPS -> GpsFixType.Dgps
        GPS_FIX_TYPE_RTK_FLOAT -> GpsFixType.RtkFloat
        GPS_FIX_TYPE_RTK_FIXED -> GpsFixType.RtkFixed
        GPS_FIX_TYPE_STATIC -> GpsFixType.Static
        GPS_FIX_TYPE_PPP -> GpsFixType.Ppp
        else -> GpsFixType.Unknown(fixType)
    }
}
