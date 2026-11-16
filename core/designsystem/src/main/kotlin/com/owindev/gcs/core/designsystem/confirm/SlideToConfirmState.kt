package com.owindev.gcs.core.designsystem.confirm

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * The rules of a slide-to-confirm control (REQ-050): a slide calls [onConfirm] only when released with the thumb at
 * the end of the track, enabled and on the key it started on, and at most once; a cancelled gesture, a key change or a
 * disable during the slide drops it and sends the thumb back.
 */
@Stable
class SlideToConfirmState(key: Any, enabled: Boolean, onConfirm: () -> Unit) {

    /** How far the thumb is from the start, in pixels: read it in layout or draw, it changes every frame. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var key: Any = key
    private var enabled: Boolean = enabled
    private var onConfirm: () -> Unit = onConfirm
    private var travel = 0f
    private var slideKey: Any? = null

    /** Takes the caller's latest values; a slide in progress is dropped if its key changed or it was disabled. */
    fun update(key: Any, enabled: Boolean, onConfirm: () -> Unit) {
        this.key = key
        this.enabled = enabled
        this.onConfirm = onConfirm
        if (!enabled || (slideKey != null && slideKey != key)) reset()
    }

    /** The distance the thumb travels from the start to the end of the track, in pixels; a new one drops a slide. */
    fun onTrackMeasured(travel: Float) {
        val measured = travel.coerceAtLeast(0f)
        if (measured != this.travel) reset()
        this.travel = measured
    }

    fun onDragStart() {
        reset()
        if (enabled) slideKey = key
    }

    fun onDrag(delta: Float) {
        if (slideKey == null) return
        offset = (offset + delta).coerceIn(0f, travel)
    }

    /** The thumb leaves the start only during an enabled slide on the current key: [update] drops any other. */
    fun onDragEnd() {
        val confirmed = travel > 0f && offset >= travel
        reset()
        if (confirmed) onConfirm()
    }

    fun onDragCancel() {
        reset()
    }

    /** The accessibility action: the same as a completed slide, in one step. Returns whether it confirmed. */
    fun confirmByAccessibility(): Boolean {
        reset()
        if (enabled) onConfirm()
        return enabled
    }

    private fun reset() {
        slideKey = null
        offset = 0f
    }
}
