package com.owindev.gcs.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/** What the activity needs from the service to start it whenever the rule says it must run (REQ-046). */
@Singleton
class BackgroundLinkState @Inject constructor() {

    val running = MutableStateFlow(false)

    /** Set by an accepted Disconnect; cleared once a live vehicle is armed, which always overrides it. */
    val disconnectedByUser = MutableStateFlow(false)
}
