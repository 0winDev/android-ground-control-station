package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkWarning
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The background link's warnings for the HUD (REQ-046); the rule shows them only while the link runs. */
@HiltViewModel
class BackgroundWarningsViewModel @Inject constructor(repository: BackgroundLinkRepository, rule: BackgroundLinkRule) :
    ViewModel() {

    val warnings: StateFlow<Set<BackgroundLinkWarning>> = repository.status
        .map(rule::warningsFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout = 5.seconds), emptySet())
}
