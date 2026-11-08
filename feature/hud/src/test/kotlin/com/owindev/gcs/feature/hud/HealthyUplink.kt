package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.link.UplinkHealth
import com.owindev.gcs.core.domain.link.UplinkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An uplink with nothing observed against it, for tests that do not look at it. */
internal fun healthyUplink(): UplinkRepository = object : UplinkRepository {
    override val health: StateFlow<UplinkHealth> = MutableStateFlow(UplinkHealth.HEALTHY)
}
