package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.geo.toMgrs
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.isPositionVerified
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The HUD's [TelemetryPanel] for [telemetry] as it is now, judged by [staleness] (REQ-026, REQ-029). */
internal fun panelOf(telemetry: Telemetry?, staleness: StalenessRule): TelemetryPanel = TelemetryPanel(
    position = telemetry?.position?.let {
        reading(it, TelemetryKind.POSITION, staleness) { position ->
            PositionReading(position, position.toMgrs(), verified = telemetry.isPositionVerified(staleness))
        }
    },
    attitude = telemetry?.attitude?.let { reading(it, TelemetryKind.ATTITUDE, staleness) { attitude -> attitude } },
    airData = telemetry?.airData?.let { reading(it, TelemetryKind.AIR_DATA, staleness) { airData -> airData } },
    battery = telemetry?.battery?.let { reading(it, TelemetryKind.BATTERY, staleness) { battery -> battery } },
    gps = telemetry?.gps?.let { reading(it, TelemetryKind.GPS, staleness) { gps -> gps } },
)

/**
 * When the panel for [telemetry] changes with nothing new arriving: a fresh value going stale, or the age of a
 * stale one reaching its next whole second. `null` when nothing will change until something arrives.
 *
 * Ages count while the device sleeps but the `delay` waiting for this does not, so after the device wakes the
 * panel can be judged up to this long late, never early, and the next telemetry judges it at once.
 */
internal fun untilPanelChanges(telemetry: Telemetry, staleness: StalenessRule): Duration? {
    val nextStale = staleness.untilNextStale(telemetry)
    val nextAgeSecond = TelemetryKind.entries
        .mapNotNull { kind -> telemetry[kind]?.receivedAt?.elapsedNow()?.takeIf { staleness.isStale(kind, it) } }
        .map { age -> AGE_UNIT - (age.inWholeNanoseconds % AGE_UNIT.inWholeNanoseconds).nanoseconds }
        .minOrNull()
    return listOfNotNull(nextStale, nextAgeSecond).minOrNull()
}

private fun <T, R> reading(
    timed: Timed<T>,
    kind: TelemetryKind,
    staleness: StalenessRule,
    value: (T) -> R,
): Reading<R> {
    val age = timed.receivedAt.elapsedNow()
    val stale = staleness.isStale(kind, age)
    return Reading(value = value(timed.value), stale = stale, age = if (stale) shownAge(age) else Duration.ZERO)
}

/** Ages are shown in whole seconds, so they only need judging again once per second. */
private val AGE_UNIT = 1.seconds
