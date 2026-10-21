package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.link.DataOrigin

/**
 * One session of the link: the live link or one replay (REQ-037). [epoch] grows with every session, so two replays one
 * after the other, or a stop and a play in the same instant, are never taken for one session, even when a conflated
 * state shows only the last of them.
 */
internal data class SessionId(val origin: DataOrigin, val epoch: Long)

/** The live session the app starts with. */
internal val FIRST_SESSION = SessionId(DataOrigin.LIVE, epoch = 0)
