package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.geo.Mgrs
import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.PositionTrust
import kotlin.time.Duration

/**
 * What the HUD shows of the active vehicle's telemetry (REQ-029). Each part is `null` until its first message
 * arrives; a value inside a part is `null` when the vehicle does not know it or sent it out of range (REQ-031),
 * and is shown as no value, never 0 nor the last one.
 */
data class TelemetryPanel(
    val position: Reading<PositionReading>? = null,
    val attitude: Reading<Attitude>? = null,
    val airData: Reading<AirData>? = null,
    val battery: Reading<Battery>? = null,
    val gps: Reading<GpsStatus>? = null,
)

/**
 * One part of the telemetry as the HUD shows it: [stale] once older than its threshold (REQ-026), when it is
 * greyed and shows [age], in whole seconds rounded up (`shownAge`); a fresh part shows no age, so it is 0.
 */
data class Reading<T>(val value: T, val stale: Boolean, val age: Duration)

/**
 * The vehicle's position and the same position in [mgrs], `Mgrs.NotAvailable` outside UTM (REQ-027). Shown as
 * current only when [trust] is `VERIFIED`, by a fresh 3D GPS fix (`isPositionVerified`); otherwise it may be 0°, 0°
 * or a frozen position, and the HUD says why.
 */
data class PositionReading(val position: GlobalPosition, val mgrs: Mgrs, val trust: PositionTrust) {
    val verified: Boolean get() = trust == PositionTrust.VERIFIED
}
