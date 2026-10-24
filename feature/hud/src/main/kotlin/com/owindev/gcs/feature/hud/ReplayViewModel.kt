package com.owindev.gcs.feature.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The replay banner and its controls (REQ-037). It starts from the replay's state as it is now, never from nothing, so
 * coming back to the flight screen during a replay never shows a frame without the banner.
 */
@HiltViewModel
class ReplayViewModel @Inject constructor(private val replay: ReplayRepository) : ViewModel() {

    val banner: StateFlow<ReplayBanner?> = combine(replay.state, replay.skippedGap, ::replayBannerOf).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        initialValue = replayBannerOf(replay.state.value, replay.skippedGap.value),
    )

    fun onPause() = replay.pause()

    fun onResume() = replay.resume()

    fun onStop() = replay.stop()

    private companion object {
        val STOP_TIMEOUT = 5.seconds
    }
}

/**
 * What the banner says while a replay is on, whatever it is doing: [recording] is the file's name, [skipped] the
 * recorded time it is jumping over now, while it plays or is paused.
 */
data class ReplayBanner(val recording: String, val status: ReplayStatus, val skipped: Duration? = null)

enum class ReplayStatus { PLAYING, PAUSED, ENDED, UNREADABLE }

internal class ReplayControls(val onPause: () -> Unit, val onResume: () -> Unit, val onStop: () -> Unit)

/** The banner for [state], `null` only when the link is the live one. */
internal fun replayBannerOf(state: ReplayState, skipped: Duration?): ReplayBanner? = when (state) {
    is ReplayState.Playing -> ReplayBanner(state.recording.name, ReplayStatus.PLAYING, skipped)

    is ReplayState.Paused -> ReplayBanner(state.recording.name, ReplayStatus.PAUSED, skipped)

    is ReplayState.Ended -> ReplayBanner(state.recording.name, ReplayStatus.ENDED)

    // Unreadable, the live link still closed until stopped; a file that was never a recording left it live.
    is ReplayState.Failed ->
        if (state.origin == DataOrigin.REPLAY) ReplayBanner(state.recording.name, ReplayStatus.UNREADABLE) else null

    ReplayState.Off, is ReplayState.Refused -> null
}
