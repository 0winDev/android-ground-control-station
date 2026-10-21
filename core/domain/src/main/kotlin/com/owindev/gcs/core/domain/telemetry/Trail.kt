package com.owindev.gcs.core.domain.telemetry

import com.owindev.gcs.core.domain.geo.GreatCircle

/** A position the vehicle passed, in degrees, WGS84. */
data class TrailPoint(val latitude: Double, val longitude: Double)

/**
 * Where the vehicle has been (REQ-035): its verified positions, oldest first, in [segments] the GCS saw without a
 * break, so a stretch it did not see is never drawn as a straight line. [open] says whether the next verified
 * position continues the last segment.
 */
data class Trail(val segments: List<List<TrailPoint>>, val open: Boolean) {

    companion object {
        val EMPTY = Trail(segments = emptyList(), open = false)
    }
}

/**
 * Which positions enter a [Trail] (REQ-035). Only a verified one ([isPositionVerified]): a position that is not
 * closes the segment, and so does a gap in positions as long as their staleness threshold, as when the link is lost
 * and nothing arrives to close it. A position extends the open segment once it is [minSpacingMetres] from its last
 * point, and the trail keeps at most [maxPoints] in all, dropping the oldest first.
 */
class TrailRule(
    private val staleness: StalenessRule,
    val minSpacingMetres: Double = DEFAULT_MIN_SPACING_METRES,
    val maxPoints: Int = DEFAULT_MAX_POINTS,
) {

    init {
        require(minSpacingMetres > 0) { "The trail spacing must be positive, was $minSpacingMetres m" }
        require(maxPoints > 0) { "The trail must hold at least one point, was $maxPoints" }
    }

    /**
     * [trail] after [telemetry]'s position, which has just arrived and followed [previousPosition], the one before it
     * from the same vehicle, `null` if none. The same [trail] when nothing changes.
     */
    fun after(trail: Trail, previousPosition: Timed<GlobalPosition>?, telemetry: Telemetry): Trail {
        val position = telemetry.position?.value
        return when {
            position == null -> trail

            !telemetry.isPositionVerified(staleness) -> if (trail.open) trail.copy(open = false) else trail

            else -> extended(
                trail,
                TrailPoint(position.latitude, position.longitude),
                continues(trail, previousPosition),
            )
        }
    }

    private fun continues(trail: Trail, previousPosition: Timed<GlobalPosition>?): Boolean = trail.open &&
        previousPosition != null &&
        !staleness.isStale(TelemetryKind.POSITION, previousPosition.receivedAt.elapsedNow())

    private fun extended(trail: Trail, point: TrailPoint, continues: Boolean): Trail {
        val last = trail.segments.lastOrNull()
        val segments = when {
            !continues || last == null -> trail.segments + listOf(listOf(point))
            isSpaced(last.last(), point) -> trail.segments.dropLast(1) + listOf(last + point)
            else -> null
        }
        return segments?.let { Trail(it.trimmedTo(maxPoints), open = true) } ?: trail
    }

    private fun isSpaced(from: TrailPoint, to: TrailPoint): Boolean =
        GreatCircle.distance(from.latitude, from.longitude, to.latitude, to.longitude) >= minSpacingMetres

    companion object {
        /**
         * Positions arrive at 5 Hz, so at ArduCopter 4.7's default waypoint speed, 10 m/s (`WP_SPD`, `WP_SPD_DEFAULT`
         * in `libraries/AC_WPNav/AC_WPNav.cpp`), each one is 2 m on and enters; hovering, the vehicle drifts less and
         * adds nothing.
         */
        const val DEFAULT_MIN_SPACING_METRES = 2.0

        /** At least 10 km of flight at that spacing, more than a 20-minute flight at 8 m/s, in about 200 KB. */
        const val DEFAULT_MAX_POINTS = 5_000
    }
}

/** Drops points from the oldest segment on, and every segment left empty, until at most [maxPoints] remain. */
private fun List<List<TrailPoint>>.trimmedTo(maxPoints: Int): List<List<TrailPoint>> {
    var excess = sumOf { it.size } - maxPoints
    if (excess <= 0) return this
    return mapNotNull { segment ->
        val dropped = minOf(excess, segment.size)
        excess -= dropped
        if (dropped == 0) segment else segment.drop(dropped).ifEmpty { null }
    }
}
