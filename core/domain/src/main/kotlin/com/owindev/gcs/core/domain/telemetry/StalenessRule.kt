package com.owindev.gcs.core.domain.telemetry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The parts of [Telemetry] whose age [StalenessRule] judges. */
enum class TelemetryKind {
    POSITION,
    ATTITUDE,
    AIR_DATA,
    BATTERY,
    GPS,
    LANDED,
    ;

    companion object {
        /** The kinds shown to the operator and alerted on when stale; the landed state only gates a take off (#99). */
        val DISPLAYED: List<TelemetryKind> = entries - LANDED
    }
}

/**
 * When a telemetry value is too old to trust (REQ-026): once its age reaches its kind's threshold.
 *
 * The age is read when the value is used (`receivedAt.elapsedNow()`), never stored. A value that stops
 * arriving brings no new emission, so a consumer must judge it again on its own clock, at least once per
 * threshold, as the HUD does with the link age: only then does it go stale on time. Freshness says nothing
 * about the link: a vehicle that is [com.owindev.gcs.core.domain.vehicle.VehicleLink.LOST] outranks any value
 * that still reads fresh.
 */
class StalenessRule(thresholds: Map<TelemetryKind, Duration> = DEFAULT_THRESHOLDS) {

    // A copy, so a caller's mutable map cannot change the thresholds after they were checked.
    private val thresholds: Map<TelemetryKind, Duration> = thresholds.toMap()

    init {
        TelemetryKind.entries.forEach { kind ->
            val threshold = requireNotNull(thresholds[kind]) { "No staleness threshold for $kind" }
            require(threshold > Duration.ZERO) { "The staleness threshold for $kind must be positive, was $threshold" }
        }
    }

    /** How old a value of [kind] may get before it is stale. */
    fun thresholdOf(kind: TelemetryKind): Duration = thresholds.getValue(kind)

    /** Whether a value of [kind] received [age] ago, on a monotonic clock, is stale. */
    fun isStale(kind: TelemetryKind, age: Duration): Boolean = age >= thresholdOf(kind)

    /**
     * How long until the next fresh value of [telemetry] the operator sees ([TelemetryKind.DISPLAYED]) goes stale: a
     * consumer must judge it again then, even if nothing new arrives, or a value that stopped arriving would never go
     * stale. `null` when no such value is fresh, so nothing will change until something arrives.
     */
    fun untilNextStale(telemetry: Telemetry): Duration? = TelemetryKind.DISPLAYED
        .mapNotNull { kind -> telemetry[kind]?.let { thresholdOf(kind) - it.receivedAt.elapsedNow() } }
        .filter { it > Duration.ZERO }
        .minOrNull()

    companion object {
        /**
         * About four or five intervals of the rate each value is requested at (10, 5, 4, 2 Hz), so one or two
         * lost messages never grey a value out, and below the 3 s link-loss timeout so that a stream that stops
         * while the vehicle is still heard shows. The battery (1 Hz) gets three intervals, the timeout itself. The
         * landed state (4 Hz) is only requested again after it, like the air data; a take off judges it on its own,
         * tighter bound ([LandedRule]).
         */
        val DEFAULT_THRESHOLDS: Map<TelemetryKind, Duration> = mapOf(
            TelemetryKind.ATTITUDE to 500.milliseconds,
            TelemetryKind.POSITION to 1.seconds,
            TelemetryKind.AIR_DATA to 1.seconds,
            TelemetryKind.GPS to 2.seconds,
            TelemetryKind.BATTERY to 3.seconds,
            TelemetryKind.LANDED to 1.seconds,
        )
    }
}
