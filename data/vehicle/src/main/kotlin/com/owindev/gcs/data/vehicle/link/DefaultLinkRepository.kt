package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import java.io.IOException
import java.net.BindException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * Counts the datagrams arriving on the UDP link.
 *
 * The transport flow is shared through one [StateFlow] in the application scope: collecting the
 * transport twice would bind the port twice, and the second bind would fail. The socket stays open
 * while anyone observes [traffic] (plus [STOP_TIMEOUT]); after that it closes and the count restarts.
 */
@Singleton
internal class DefaultLinkRepository @Inject constructor(
    private val transport: UdpTransport,
    @param:ApplicationScope private val scope: CoroutineScope,
) : LinkRepository {

    // Built on first use, so the transport is only asked for its flow once someone observes the link.
    override val traffic: StateFlow<LinkTraffic> by lazy {
        transport.datagrams()
            .runningFold(initial = 0L) { count, _ -> count + 1 }
            .map<Long, LinkTraffic> { count -> LinkTraffic.Receiving(port = transport.port, datagrams = count) }
            .catch { failure -> emit(failure.toLinkTraffic()) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                initialValue = LinkTraffic.Receiving(port = transport.port, datagrams = 0),
            )
    }

    private fun Throwable.toLinkTraffic(): LinkTraffic = when (this) {
        is BindException -> LinkTraffic.Unavailable(port = transport.port)

        is IOException -> {
            // The UI only says "link error"; the cause goes to logcat so the failure can be diagnosed.
            Log.w(TAG, "Vehicle link failed unexpectedly", this)
            LinkTraffic.Failed
        }

        else -> throw this
    }

    private companion object {
        const val TAG = "LinkRepository"

        /** Keeps the socket open across short gaps without observers, such as a configuration change. */
        val STOP_TIMEOUT = 5.seconds
    }
}
