package com.owindev.gcs.data.vehicle.link

import java.io.IOException

/** The state of the shared [LinkSession]: kept apart from its messages so it can be read at any time. */
internal sealed interface LinkStatus {

    /** Listening; [datagrams] have arrived since the session started. */
    data class Running(val datagrams: Long) : LinkStatus

    /** The port could not be bound because it is already in use (e.g. by another app). */
    data object Unavailable : LinkStatus

    /** Receiving stopped because of an unexpected I/O error. */
    data class Failed(val cause: IOException) : LinkStatus
}
