package com.owindev.gcs.data.vehicle.recording

import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

/** A recorder for tests that are not about recording: it takes every frame and writes nothing. */
internal fun ignoringRecorder(): FlightRecorder {
    val recorder: FlightRecorder = mockk()
    every { recorder.received(any(), any()) } just runs
    every { recorder.sent(any()) } just runs
    every { recorder.sessionStopped() } just runs
    every { recorder.replayed = any() } just runs
    return recorder
}
