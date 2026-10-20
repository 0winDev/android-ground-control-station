package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A session for tests that only need to know whether the link is live: on [origin], live unless a test changes it. */
internal fun sessionOn(origin: StateFlow<DataOrigin>): LinkSession {
    val session: LinkSession = mockk()
    every { session.origin } returns origin
    return session
}

/** A session that is always live. */
internal fun liveSession(): LinkSession = sessionOn(MutableStateFlow(DataOrigin.LIVE))
