package com.owindev.gcs.core.transport

import kotlinx.coroutines.flow.Flow

/** Where the vehicle link's datagrams come from: the UDP socket, or a recording played back. */
interface DatagramSource {

    /**
     * A cold flow of the datagrams in arrival order. Each collection starts the source again, so share it instead of
     * collecting it twice. A source that cannot deliver fails with an [java.io.IOException].
     */
    fun datagrams(): Flow<Datagram>
}
