package com.owindev.gcs.data.vehicle.link

import java.io.IOException

/** The state of the shared [LinkSession]: kept apart from its messages so it can be read at any time. */
internal sealed interface LinkStatus {

    /** Listening; [datagrams] have arrived since the session started. */
    data class Running(val datagrams: Long) : LinkStatus

    /**
     * Reopened after a failure and receiving again, but not yet up for [LINK_STABLE_AFTER]; [datagrams] as in
     * [Running]. A socket that fails after delivering datagrams keeps this state, so a flapping link does not
     * flicker between [Failed] and [Running].
     */
    data class Recovering(val datagrams: Long) : LinkStatus

    /**
     * The port could not be bound on the session's first attempt because it is already in use (e.g. by
     * another app): never retried, the operator has to free it.
     */
    data object Unavailable : LinkStatus

    /** The port was taken while the link was being reopened after a failure: retried with the backoff. */
    data object UnavailableRetrying : LinkStatus

    /** Receiving stopped because of an unexpected I/O error. */
    data class Failed(val cause: IOException) : LinkStatus
}
