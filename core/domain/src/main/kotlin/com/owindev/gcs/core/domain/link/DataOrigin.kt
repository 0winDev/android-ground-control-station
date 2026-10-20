package com.owindev.gcs.core.domain.link

/** Where a value came from: the vehicle's link now, or a recording played back (REQ-037), never to be confused. */
enum class DataOrigin {
    LIVE,
    REPLAY,
}
