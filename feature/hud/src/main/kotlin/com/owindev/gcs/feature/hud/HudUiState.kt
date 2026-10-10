package com.owindev.gcs.feature.hud

/** What the HUD shows about the vehicle link. */
sealed interface HudUiState {

    data class Receiving(val port: Int, val datagramCount: Long) : HudUiState

    data class PortUnavailable(val port: Int) : HudUiState

    data object LinkError : HudUiState
}
