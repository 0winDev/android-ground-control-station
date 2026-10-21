package com.owindev.gcs.core.domain.time

import kotlin.time.TimeSource

/**
 * The device's own time, which a replay never pauses (REQ-037): for what measures the operator rather than the vehicle,
 * such as how long an alert has been on screen. Everything about the vehicle runs on the pipeline's time instead.
 */
class DeviceClock(device: TimeSource.WithComparableMarks) : TimeSource.WithComparableMarks by device
