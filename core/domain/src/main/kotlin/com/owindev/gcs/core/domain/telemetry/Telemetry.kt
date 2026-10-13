package com.owindev.gcs.core.domain.telemetry

/**
 * The last telemetry received from one vehicle, each part with the time it arrived (REQ-026). A part is
 * `null` until its first message arrives; a part that stops arriving keeps its last value and grows stale.
 *
 * Units are SI except angles, which are in degrees: latitude, longitude, headings, courses and attitude.
 * A field that the vehicle reported as unknown is `null`, never 0.
 */
data class Telemetry(
    val position: Timed<GlobalPosition>? = null,
    val attitude: Timed<Attitude>? = null,
    val airData: Timed<AirData>? = null,
    val battery: Timed<Battery>? = null,
    val gps: Timed<GpsStatus>? = null,
) {

    /** The part of [kind], to judge its age with [StalenessRule]; `null` until its first message. */
    operator fun get(kind: TelemetryKind): Timed<*>? = when (kind) {
        TelemetryKind.POSITION -> position
        TelemetryKind.ATTITUDE -> attitude
        TelemetryKind.AIR_DATA -> airData
        TelemetryKind.BATTERY -> battery
        TelemetryKind.GPS -> gps
    }
}

/**
 * The vehicle's estimated position and velocity, fused from GPS and its other sensors.
 *
 * @property latitude in degrees, WGS84.
 * @property longitude in degrees, WGS84.
 * @property altitudeMsl above mean sea level, in m.
 * @property altitudeAboveHome above the home position, in m: not above the ground under the vehicle.
 * @property velocityNorth in m/s.
 * @property velocityEast in m/s.
 * @property velocityDown in m/s: negative while climbing.
 * @property heading where the vehicle points, 0 (north) up to 360 degrees, or `null` if unknown.
 */
data class GlobalPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMsl: Double,
    val altitudeAboveHome: Double,
    val velocityNorth: Double,
    val velocityEast: Double,
    val velocityDown: Double,
    val heading: Double?,
)

/**
 * The vehicle's attitude, each angle in degrees from -180 to 180 as the vehicle sends it; float rounding of
 * ±π can put it a few millionths of a degree past ±180.
 */
data class Attitude(val roll: Double, val pitch: Double, val yaw: Double)

/**
 * What a HUD shows of the flight.
 *
 * @property airspeed in m/s, as the vehicle estimates it.
 * @property groundspeed in m/s.
 * @property climbRate in m/s: positive while climbing.
 * @property throttle in percent, 0 to 100.
 */
data class AirData(val airspeed: Double, val groundspeed: Double, val climbRate: Double, val throttle: Int)

/**
 * The vehicle's main battery, each reading `null` when the vehicle does not send it.
 *
 * @property voltage in V.
 * @property current in A: negative while charging.
 * @property remaining energy left, in percent from 0 to 100.
 */
data class Battery(val voltage: Double?, val current: Double?, val remaining: Int?)

/**
 * The quality of the vehicle's GPS fix, as its GPS receiver reports it. Each reading is `null` when the
 * receiver does not know it.
 *
 * @property satellitesVisible how many satellites the receiver sees.
 * @property hdop horizontal dilution of precision (unitless).
 * @property vdop vertical dilution of precision (unitless).
 * @property groundSpeed in m/s.
 * @property courseOverGround the direction of movement, not the heading, from 0 (north) up to 360 degrees.
 * @property horizontalAccuracy position uncertainty, in m.
 * @property verticalAccuracy altitude uncertainty, in m.
 * @property yaw the heading a dual-antenna receiver measures, from 0 (north) up to 360 degrees.
 */
data class GpsStatus(
    val fixType: GpsFixType,
    val satellitesVisible: Int?,
    val hdop: Double?,
    val vdop: Double?,
    val groundSpeed: Double?,
    val courseOverGround: Double?,
    val horizontalAccuracy: Double?,
    val verticalAccuracy: Double?,
    val yaw: Double?,
)
