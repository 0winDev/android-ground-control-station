package com.owindev.gcs.feature.map

import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import com.owindev.gcs.core.domain.telemetry.Trail
import com.owindev.gcs.core.domain.telemetry.isPositionVerified
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.Vehicle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The map's [MapUiState] for the active [vehicle], its [telemetry] and [trail] as they are now, camera aside. The
 * vehicle is drawn only at a real place: never at 0°, 0°, which ArduPilot sends before it has a position (#42).
 */
internal fun mapStateOf(
    vehicle: Vehicle?,
    telemetry: Telemetry?,
    trail: Trail?,
    linkLossRule: LinkLossRule,
    staleness: StalenessRule,
): MapUiState {
    // A vehicle and telemetry from different sessions are never paired (REQ-037).
    val kept = telemetry?.takeIf { vehicle != null && it.origin == vehicle.origin }
    val position = kept?.position
    val place = position?.value?.takeIf { !(it.latitude == 0.0 && it.longitude == 0.0) }
    return MapUiState(
        vehicle = if (vehicle != null && position != null && place != null) {
            MapVehicle(
                systemId = vehicle.state.systemId.value,
                position = MapPoint(place.latitude, place.longitude),
                heading = place.heading,
                altitudeAboveHome = place.altitudeAboveHome,
                shown = shownOf(vehicle, kept, position.receivedAt.elapsedNow(), linkLossRule, staleness),
            )
        } else {
            null
        },
        home = kept?.home?.value?.let { MapPoint(it.latitude, it.longitude) },
        trail = trail?.segments.orEmpty().map { segment -> segment.map { MapPoint(it.latitude, it.longitude) } },
    )
}

/** How long until [mapStateOf] would give something else with nothing new arriving; `null` if never. */
internal fun untilMapChanges(
    vehicle: Vehicle?,
    telemetry: Telemetry?,
    linkLossRule: LinkLossRule,
    staleness: StalenessRule,
): Duration? {
    if (vehicle == null) return null
    val lost = linkLossRule.isLost(vehicle)
    val nextStale = telemetry?.let(staleness::untilNextStale)
    val untilLost = (linkLossRule.timeout - vehicle.lastHeartbeat.elapsedNow()).takeIf { !lost && it > Duration.ZERO }
    val positionAge = telemetry?.position?.receivedAt?.elapsedNow()
    val shownAge = positionAge?.takeIf { lost || staleness.isStale(TelemetryKind.POSITION, it) }
    val nextAgeSecond = shownAge?.let { AGE_UNIT - (it.inWholeNanoseconds % AGE_UNIT.inWholeNanoseconds).nanoseconds }
    return listOfNotNull(nextStale, untilLost, nextAgeSecond).minOrNull()
}

private fun shownOf(
    vehicle: Vehicle,
    telemetry: Telemetry,
    positionAge: Duration,
    linkLossRule: LinkLossRule,
    staleness: StalenessRule,
): PositionShown = when {
    linkLossRule.isLost(vehicle) -> PositionShown.LinkLost(shownAge(positionAge))
    staleness.isStale(TelemetryKind.POSITION, positionAge) -> PositionShown.Stale(shownAge(positionAge))
    !telemetry.isPositionVerified(staleness) -> PositionShown.NotVerified
    else -> PositionShown.Live
}

/** The HUD's rounding: whole seconds passed plus one, so a position never reads "0 s ago" while it is greyed. */
private fun shownAge(age: Duration): Duration = (age.inWholeSeconds + 1).seconds

/** Ages are shown in whole seconds, so they only need judging again once per second. */
private val AGE_UNIT = 1.seconds
