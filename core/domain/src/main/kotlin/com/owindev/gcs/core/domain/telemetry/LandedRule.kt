package com.owindev.gcs.core.domain.telemetry

/**
 * Whether a vehicle counts as on the ground (REQ-052): only on a fresh [LandedState.ON_GROUND]. Unknown, never
 * received or stale is never on the ground, so whatever needs the ground (a take off, #99) is refused, never assumed.
 */
class LandedRule(private val staleness: StalenessRule) {

    fun isOnGround(telemetry: Telemetry?): Boolean {
        val landed = telemetry?.landed ?: return false
        return landed.value == LandedState.ON_GROUND &&
            !staleness.isStale(TelemetryKind.LANDED, landed.receivedAt.elapsedNow())
    }
}
