package com.owindev.gcs.core.domain.telemetry

/**
 * Whether the vehicle's [Telemetry.position] can be shown as current (REQ-029): only while a fresh GPS report
 * has a 3D fix, and never at exactly 0°, 0°.
 *
 * A fresh GLOBAL_POSITION_INT is not enough. ArduPilot sends it whether or not its AHRS has a position: 0°, 0°
 * before it ever had one (seen in SITL at boot, #42), and the last one, frozen, once it loses it ("return value
 * ignored; we send stale data", `send_global_position_int` in `libraries/GCS_MAVLink/GCS_Common.cpp`), each time
 * in a new message that never goes stale. The same holds for `alt` (MSL), taken from that location. The height
 * above home is never frozen (`get_relative_position_D_home` in `libraries/AP_AHRS/AP_AHRS.cpp` always sets it,
 * from the EKF, or from the barometer or GPS height before an origin), so the HUD keeps showing it.
 *
 * The GPS fix is the driver's status, not the EKF's: the EKF sets its origin only after the GPS has passed its
 * checks for a while, and until then ArduPilot still sends 0°, 0° with a 3D fix. 0°, 0° exactly is ArduPilot's
 * uninitialised location, so it is never verified; a frozen position while the GPS keeps its fix is not caught.
 *
 * Known limit: navigation without GPS (optical flow, visual odometry, beacons) is shown as not verified. The right
 * source is the EKF's own status, EKF_STATUS_REPORT, defined in `ardupilotmega.xml` rather than `common.xml`: a
 * future issue (v0.4 or later), with the decision to use a message outside `common`.
 */
fun Telemetry.isPositionVerified(staleness: StalenessRule): Boolean {
    val gps = gps
    val location = position?.value
    return gps != null &&
        location != null &&
        !(location.latitude == 0.0 && location.longitude == 0.0) &&
        !staleness.isStale(TelemetryKind.GPS, gps.receivedAt.elapsedNow()) &&
        gps.value.fixType.isFix3d
}
