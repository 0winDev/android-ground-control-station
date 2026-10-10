package com.owindev.gcs.data.vehicle.link

import android.util.Log
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import com.owindev.gcs.data.vehicle.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Counts the datagrams arriving on the UDP link, from the status of the shared [LinkSession].
 *
 * The count restarts when the session does: after nobody has observed the link for [LINK_STOP_TIMEOUT].
 */
@Singleton
internal class DefaultLinkRepository @Inject constructor(
    private val session: LinkSession,
    @param:ApplicationScope private val scope: CoroutineScope,
) : LinkRepository {

    override val traffic: StateFlow<LinkTraffic> by lazy {
        session.whileOpen(session.status)
            .map { status -> status.toLinkTraffic() }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(LINK_STOP_TIMEOUT),
                initialValue = LinkTraffic.Receiving(port = session.port, datagrams = 0),
            )
    }

    private fun LinkStatus.toLinkTraffic(): LinkTraffic = when (this) {
        is LinkStatus.Running -> LinkTraffic.Receiving(port = session.port, datagrams = datagrams)

        LinkStatus.Unavailable -> LinkTraffic.Unavailable(port = session.port)

        is LinkStatus.Failed -> {
            // The UI only says "link error"; the cause goes to logcat so the failure can be diagnosed.
            Log.w(TAG, "Vehicle link failed unexpectedly", cause)
            LinkTraffic.Failed
        }
    }

    private companion object {
        const val TAG = "LinkRepository"
    }
}
