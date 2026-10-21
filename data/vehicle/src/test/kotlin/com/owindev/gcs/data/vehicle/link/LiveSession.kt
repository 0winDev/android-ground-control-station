package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A session for tests that only need to know which link session it is in: [session], as a test sets it. */
internal fun sessionOn(session: StateFlow<SessionId>): LinkSession {
    val linkSession: LinkSession = mockk()
    every { linkSession.session } returns session
    return linkSession
}

/** A session that is always the first live one. */
internal fun liveSession(): LinkSession = sessionOn(MutableStateFlow(FIRST_SESSION))

/** A replay session, as the link would start one. */
internal val REPLAY_SESSION = SessionId(DataOrigin.REPLAY, epoch = 1)
