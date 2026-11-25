package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.message.MessageRepository
import com.owindev.gcs.core.domain.message.VehicleMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A vehicle that sent no message, for tests that do not look at them. */
internal fun noMessages(): MessageRepository = object : MessageRepository {
    override val activeMessages: StateFlow<List<VehicleMessage>> = MutableStateFlow(emptyList())
    override val preArmReason: StateFlow<VehicleMessage?> = MutableStateFlow(null)
}
