package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.geo.toMgrs
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.telemetry.positionTrust
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/**
 * The HUD's [TelemetryPanel] for [telemetry] as it is now, judged by [staleness] (REQ-026, REQ-029).
 *
 * With [lostSince], the vehicle's last HEARTBEAT, the link is lost (REQ-017) and the panel is its last data, all of it
 * as old as that HEARTBEAT, which the banner and the panel's header count: a part shows an age of its own only when
 * it had already stopped arriving then, so it is older than the rest, and that age is in whole seconds, as theirs.
 */
internal fun panelOf(telemetry: Telemetry?, staleness: StalenessRule, lostSince: TimeMark? = null): TelemetryPanel =
    TelemetryPanel(
        position = telemetry?.position?.let {
            reading(it, TelemetryKind.POSITION, staleness, lostSince) { position ->
                PositionReading(position, position.toMgrs(), trust = telemetry.positionTrust(staleness))
            }
        },
        attitude = telemetry?.attitude?.let { part ->
            reading(part, TelemetryKind.ATTITUDE, staleness, lostSince) { it }
        },
        airData = telemetry?.airData?.let { part ->
            reading(part, TelemetryKind.AIR_DATA, staleness, lostSince) { it }
        },
        battery = telemetry?.battery?.let { part ->
            reading(part, TelemetryKind.BATTERY, staleness, lostSince) { it }
        },
        gps = telemetry?.gps?.let { part -> reading(part, TelemetryKind.GPS, staleness, lostSince) { it } },
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
    lostSince: TimeMark?,
    value: (T) -> R,
): Reading<R> {
    val age = timed.receivedAt.elapsedNow()
    if (lostSince != null) {
        val staleAtLoss = staleness.isStale(kind, age - lostSince.elapsedNow())
        val ownAge = if (staleAtLoss) age.inWholeSeconds.seconds else Duration.ZERO
        return Reading(value = value(timed.value), stale = staleAtLoss, age = ownAge)
    }
    val stale = staleness.isStale(kind, age)
    return Reading(value = value(timed.value), stale = stale, age = if (stale) shownAge(age) else Duration.ZERO)
}

/** Ages are shown in whole seconds, so they only need judging again once per second. */
private val AGE_UNIT = 1.seconds
