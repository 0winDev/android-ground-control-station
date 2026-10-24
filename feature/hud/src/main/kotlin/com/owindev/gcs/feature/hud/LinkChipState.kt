package com.owindev.gcs.feature.hud

/**
 * What the link chip says, decided apart from Compose so it can be tested. It agrees with the body below it: never
 * WAITING while the link itself has failed, and never CONNECTED while a recording plays, so nothing of a replay looks
 * live (REQ-037).
 */
internal enum class LinkChipState { NONE, CONNECTED, REPLAY, WAITING, FAILED, LOST }

internal fun linkChipStateOf(state: HudUiState): LinkChipState = when (state) {
    // A null port is a recording playing, not a socket.
    is HudUiState.Connected -> if (state.port == null) LinkChipState.REPLAY else LinkChipState.CONNECTED

    is HudUiState.WaitingForVehicle -> if (state.port == null) LinkChipState.REPLAY else LinkChipState.WAITING

    is HudUiState.PortUnavailable,
    is HudUiState.PortUnavailableRetrying,
    HudUiState.LinkError,
    HudUiState.ReplayUnreadable,
    -> LinkChipState.FAILED

    // The emergency over the map says RECORDED when it is a replay's.
    is HudUiState.LinkLost -> LinkChipState.LOST

    HudUiState.Starting -> LinkChipState.NONE
}
