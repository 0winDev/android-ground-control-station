package com.owindev.gcs.core.domain.vehicle

import kotlin.time.TimeMark

/**
 * A vehicle heard on the link: its last reported [state], whether it is still being heard ([link]), and
 * when its last HEARTBEAT arrived.
 *
 * While the link is [VehicleLink.LOST], [state] is the last known one, not the current one. The time
 * since the last HEARTBEAT is `lastHeartbeat.elapsedNow()`, read on a monotonic clock when needed, so this
 * value does not have to change every second.
 */
data class Vehicle(val state: VehicleState, val link: VehicleLink, val lastHeartbeat: TimeMark)
