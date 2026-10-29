package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.LandedState
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.core.mavlink.MavlinkMessage.ExtendedSysState
import com.owindev.gcs.core.mavlink.MavlinkMessage.GlobalPositionInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.GpsRawInt
import com.owindev.gcs.core.mavlink.MavlinkMessage.HomePosition
import com.owindev.gcs.core.mavlink.MavlinkMessage.SysStatus
import com.owindev.gcs.core.mavlink.MavlinkMessage.VfrHud
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import kotlin.time.TimeMark

/**
 * Maps the telemetry messages to the domain: SI units except angles, which are in degrees, and every
 * "unknown" value to `null`, never to 0. A value outside its documented range ([TelemetryRanges], REQ-031) is
 * `null` too, never clamped or wrapped.
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

    /**
     * 0°, 0° is what a location never set reads as: ArduPilot sends GLOBAL_POSITION_INT at 0°, 0° before it has a
     * position (#42), so a home there is taken as no home.
     */
    private const val NULL_ISLAND_DEGE7 = 0

    // GPS_FIX_TYPE (common.xml).

    /** `MAV_LANDED_STATE` (`common.xml`); 0 (UNDEFINED) and any other value are [LandedState.UNKNOWN]. */
    private val LANDED_STATES = mapOf(
        1 to LandedState.ON_GROUND,
        2 to LandedState.IN_AIR,
        3 to LandedState.TAKING_OFF,
        4 to LandedState.LANDING,
    )

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
     * `MAV_SYS_STATUS_SENSOR_BATTERY` (`common.xml`). ArduPilot always sets it in `onboard_control_sensors_present`,
     * sets it in `_enabled` only when it has a battery monitor, and in `_health` only while the monitor is healthy
     * and its battery failsafe has not triggered (https://github.com/ArduPilot/ardupilot,
     * `libraries/GCS_MAVLink/GCS.cpp` at commit 0922bced). Without a monitor, or before it has a reading, it sends
     * `voltage_battery` 0 rather than the UINT16_MAX the definition gives (`AP_BattMonitor::gcs_voltage`), as the
     * real boot frame in `truncated/sys_status.bin` shows: so 0 mV only counts as 0 V from an enabled, healthy
     * monitor (ADR 0003: autopilot-specific, kept here).
     */
    private const val MAV_SYS_STATUS_SENSOR_BATTERY = 0x0200_0000L

    /**
     * Extension fields a sender omits or truncates read as 0 (#35, #38), so a 0 accuracy is no reading, not a
     * perfect one.
     */
    private const val ACCURACY_ZERO_FILLED = 0L

    /**
     * [telemetry] with the part [received] carries, marked [receivedAt], or `null` if [received] is not
     * telemetry from a vehicle's autopilot, or is a position or home that does not exist: that frame is dropped, so
     * the last valid one is kept (REQ-031), never replaced by nothing.
     */
    fun update(telemetry: Telemetry, received: ReceivedMessage, receivedAt: TimeMark): Telemetry? {
        if (received.componentId != MAV_COMP_ID_AUTOPILOT1) return null
        return when (val message = received.message) {
            is GlobalPositionInt -> message.toPosition()?.let { telemetry.copy(position = Timed(it, receivedAt)) }

            is MavlinkMessage.Attitude -> telemetry.copy(attitude = Timed(message.toAttitude(), receivedAt))

            is VfrHud -> telemetry.copy(airData = Timed(message.toAirData(), receivedAt))

            is SysStatus -> telemetry.copy(battery = Timed(message.toBattery(), receivedAt))

            is GpsRawInt -> telemetry.copy(gps = Timed(message.toGpsStatus(), receivedAt))

            is HomePosition -> message.toHome()?.let { telemetry.copy(home = Timed(it, receivedAt)) }

            is ExtendedSysState -> telemetry.copy(
                landed = Timed(LANDED_STATES[message.landedState] ?: LandedState.UNKNOWN, receivedAt),
            )

            else -> null
        }
    }

    /** `null` for a place that does not exist. */
    private fun GlobalPositionInt.toPosition(): GlobalPosition? {
        if (!TelemetryRanges.isOnEarth(lat, lon)) return null
        return GlobalPosition(
            latitude = lat / DEGE7_PER_DEGREE,
            longitude = lon / DEGE7_PER_DEGREE,
            altitudeMsl = alt / MILLI,
            altitudeAboveHome = relativeAlt / MILLI,
            velocityNorth = vx / CENTI,
            velocityEast = vy / CENTI,
            velocityDown = vz / CENTI,
            heading = TelemetryRanges.direction(hdg)?.let { it / CENTI },
        )
    }

    /** `null` for a place that does not exist or was never set. The local `x/y/z`, `q` and approach are not used. */
    private fun HomePosition.toHome(): Home? {
        val neverSet = latitude == NULL_ISLAND_DEGE7 && longitude == NULL_ISLAND_DEGE7
        if (neverSet || !TelemetryRanges.isOnEarth(latitude, longitude)) return null
        return Home(
            latitude = latitude / DEGE7_PER_DEGREE,
            longitude = longitude / DEGE7_PER_DEGREE,
            altitudeMsl = altitude / MILLI,
        )
    }

    private fun MavlinkMessage.Attitude.toAttitude() = Attitude(
        roll = degreesOf(roll),
        pitch = degreesOf(pitch),
        yaw = degreesOf(yaw),
    )

    private fun degreesOf(radians: Float): Double? =
        TelemetryRanges.angle(radians)?.let { Math.toDegrees(it.toDouble()) }

    private fun VfrHud.toAirData() = AirData(
        airspeed = TelemetryRanges.finite(airspeed)?.toDouble(),
        groundspeed = TelemetryRanges.finite(groundspeed)?.toDouble(),
        climbRate = TelemetryRanges.finite(climb)?.toDouble(),
        throttle = TelemetryRanges.throttle(throttle),
    )

    private fun SysStatus.toBattery(): Battery {
        val monitored = onboardControlSensorsEnabled and MAV_SYS_STATUS_SENSOR_BATTERY != 0L
        val healthy = onboardControlSensorsHealth and MAV_SYS_STATUS_SENSOR_BATTERY != 0L
        return Battery(
            // ArduPilot keeps sending the voltage after its battery failsafe clears the health bit (`send_sys_status`
            // in `libraries/GCS_MAVLink/GCS_Common.cpp`), so an unhealthy monitor's reading is kept, flagged by
            // `healthy`; only its 0 mV is no reading, as at boot.
            voltage = voltageBattery
                .takeIf { monitored && it != SysStatus.VOLTAGE_BATTERY_UNKNOWN && (healthy || it != 0) }
                ?.let { it / MILLI },
            current = currentBattery.takeUnless { it == SysStatus.CURRENT_BATTERY_UNKNOWN }?.let { it / CENTI },
            // -1 is the documented "not sent"; any other value outside 0–100 is no percentage either.
            remaining = batteryRemaining.takeIf { it in 0..PERCENT_MAX },
            monitored = monitored,
            healthy = healthy,
        )
    }

    private fun GpsRawInt.toGpsStatus() = GpsStatus(
        fixType = gpsFixTypeOf(fixType),
        satellitesVisible = satellitesVisible.takeUnless { it == GpsRawInt.SATELLITES_VISIBLE_UNKNOWN },
        hdop = eph.takeUnless { it == GpsRawInt.EPH_UNKNOWN }?.let { it / CENTI },
        vdop = epv.takeUnless { it == GpsRawInt.EPV_UNKNOWN }?.let { it / CENTI },
        groundSpeed = vel.takeUnless { it == GpsRawInt.VEL_UNKNOWN }?.let { it / CENTI },
        courseOverGround = TelemetryRanges.direction(cog)?.let { it / CENTI },
        horizontalAccuracy = hAcc.takeUnless { it == ACCURACY_ZERO_FILLED }?.let { it / MILLI },
        verticalAccuracy = vAcc.takeUnless { it == ACCURACY_ZERO_FILLED }?.let { it / MILLI },
        yaw = gpsYawOf(yaw),
    )

    /**
     * 0 is "not provided" and UINT16_MAX "unable to provide it", so north is sent as 36000; anything past 36000 is
     * no yaw either.
     */
    private fun gpsYawOf(yaw: Int): Double? = when (yaw) {
        GpsRawInt.YAW_NORTH -> NORTH_DEGREES
        in 1 until GpsRawInt.YAW_NORTH -> yaw / CENTI
        else -> null
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
