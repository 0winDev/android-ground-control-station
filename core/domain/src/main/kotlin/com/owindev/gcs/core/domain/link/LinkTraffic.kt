package com.owindev.gcs.core.domain.link

/** What is arriving on the vehicle link. Infrastructure failures are states, never exceptions. */
sealed interface LinkTraffic {

    /** Listening on [port]; [datagrams] have arrived since the link was opened. */
    data class Receiving(val port: Int, val datagrams: Long) : LinkTraffic

    /**
     * Receiving again on [port] after the link failed, but not for long enough to call it recovered: the link
     * may still be flapping. [datagrams] as in [Receiving].
     */
    data class Recovering(val port: Int, val datagrams: Long) : LinkTraffic

    /**
     * The link could not listen on [port] when it opened because it is already in use (e.g. by another app).
     * Not retried: the operator has to free the port.
     */
    data class Unavailable(val port: Int) : LinkTraffic

    /** [port] was taken while the link was being reopened after a failure; the link keeps retrying. */
    data class UnavailableRetrying(val port: Int) : LinkTraffic

    /** A recording is being played back instead of the live link (REQ-037); [datagrams] have been played so far. */
    data class Replaying(val datagrams: Long) : LinkTraffic

    /** The link stopped because of an unexpected I/O error. */
    data object Failed : LinkTraffic
}
