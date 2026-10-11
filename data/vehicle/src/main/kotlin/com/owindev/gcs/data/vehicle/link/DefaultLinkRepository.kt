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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Counts the datagrams arriving on the UDP link, from the status of the shared [LinkSession].
 *
 * The count restarts when the session does: after nobody has observed the link for [LINK_STOP_TIMEOUT]. A
 * transport failure shows as [LinkTraffic.Failed] while the session reopens the link by itself (REQ-019).
 */
@Singleton
internal class DefaultLinkRepository @Inject constructor(
    private val session: LinkSession,
    @param:ApplicationScope private val scope: CoroutineScope,
) : LinkRepository {

    /** Only the [traffic] collector touches it. */
    private var failing = false

    override val traffic: StateFlow<LinkTraffic> by lazy {
        session.whileOpen(session.status)
            .onEach(::logTransition)
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
        is LinkStatus.Failed -> LinkTraffic.Failed
    }

    /**
     * The UI only says "link error"; the cause goes to logcat so the failure can be diagnosed. Once per
     * outage, not once per reopening attempt, and once more when datagrams arrive again.
     */
    private fun logTransition(status: LinkStatus) {
        when {
            status is LinkStatus.Failed && !failing -> {
                Log.w(TAG, "Vehicle link failed unexpectedly; reopening it", status.cause)
                failing = true
            }

            status is LinkStatus.Running && status.datagrams > 0 && failing -> {
                Log.i(TAG, "Vehicle link reopened")
                failing = false
            }

            // Running(0) only starts a session: a new session has no outage to recover from yet.
            status == LinkStatus.Running(datagrams = 0) -> failing = false
        }
    }

    private companion object {
        const val TAG = "LinkRepository"
    }
}
