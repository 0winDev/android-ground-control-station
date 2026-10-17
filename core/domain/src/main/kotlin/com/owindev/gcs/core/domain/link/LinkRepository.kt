package com.owindev.gcs.core.domain.link

import kotlinx.coroutines.flow.StateFlow

/** The link to the vehicle. Implemented in `:data:vehicle` and bound there with Hilt. */
interface LinkRepository {

    /** Traffic on the link; the link is open while this is collected. */
    val traffic: StateFlow<LinkTraffic>
}
