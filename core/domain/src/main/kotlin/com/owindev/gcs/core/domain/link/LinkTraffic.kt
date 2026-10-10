package com.owindev.gcs.core.domain.link

/** What is arriving on the vehicle link. Infrastructure failures are states, never exceptions. */
sealed interface LinkTraffic {

    /** Listening on [port]; [datagrams] have arrived since the link was opened. */
    data class Receiving(val port: Int, val datagrams: Long) : LinkTraffic

    /** The link could not listen on [port] because it is already in use (e.g. by another app). */
    data class Unavailable(val port: Int) : LinkTraffic

    /** The link stopped because of an unexpected I/O error. */
    data object Failed : LinkTraffic
}
