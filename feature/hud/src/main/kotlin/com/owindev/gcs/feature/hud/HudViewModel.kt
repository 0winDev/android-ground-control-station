package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HudViewModel @Inject constructor(linkRepository: LinkRepository) : ViewModel() {

    val state: StateFlow<HudUiState> = linkRepository.traffic
        .map(LinkTraffic::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            initialValue = linkRepository.traffic.value.toUiState(),
        )

    private companion object {
        /** Survives a configuration change without closing the link. */
        val STOP_TIMEOUT = 5.seconds
    }
}

private fun LinkTraffic.toUiState(): HudUiState = when (this) {
    is LinkTraffic.Receiving -> HudUiState.Receiving(port = port, datagramCount = datagrams)
    is LinkTraffic.Unavailable -> HudUiState.PortUnavailable(port = port)
    LinkTraffic.Failed -> HudUiState.LinkError
}
