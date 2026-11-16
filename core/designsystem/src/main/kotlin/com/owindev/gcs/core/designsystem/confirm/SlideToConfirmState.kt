package com.owindev.gcs.core.designsystem.confirm

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * The rules of a slide-to-confirm control (REQ-050). A slide starts only on the thumb and calls [onConfirm] with the
 * key it started on only when released with the thumb at the end of a track at least the minimum travel long, at most
 * once. A cancelled gesture, a disable, a new track size or a change of the action, the system ID or the key during
 * the slide drops it and sends the thumb back. A second pointer may carry a slide on after the first lifts.
 */
@Stable
class SlideToConfirmState<K : Any>(
    action: String,
    systemId: Int,
    confirmKey: K,
    enabled: Boolean,
    onConfirm: (K) -> Unit,
) {

    /** How far the thumb is from the start, in pixels: read it in layout or draw, it changes every frame. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var key = SlideKey(action, systemId, confirmKey)
    private var enabled = enabled
    private var onConfirm = onConfirm
    private var thumb = 0f
    private var travel = 0f
    private var minimumTravel = 0f
    private var startedOn: SlideKey<K>? = null

    /** Takes the caller's latest values; a slide whose action, system ID or key changed, or that is disabled, drops. */
    fun update(action: String, systemId: Int, confirmKey: K, enabled: Boolean, onConfirm: (K) -> Unit) {
        key = SlideKey(action, systemId, confirmKey)
        this.enabled = enabled
        this.onConfirm = onConfirm
        if (!enabled || (startedOn != null && startedOn != key)) reset()
    }

    /** Track and thumb widths and the shortest travel that may confirm, in pixels; a change drops a slide. */
    fun onTrackMeasured(width: Float, thumb: Float, minimumTravel: Float) {
        val travel = (width - thumb).coerceAtLeast(0f)
        if (travel != this.travel || thumb != this.thumb || minimumTravel != this.minimumTravel) reset()
        this.thumb = thumb
        this.travel = travel
        this.minimumTravel = minimumTravel
    }

    /**
     * A drag noticed [position] pixels from the start of the track, along the slide; [slop] is how far the pointer may
     * have moved off the thumb before the drag was noticed.
     */
    fun onDragStart(position: Float, slop: Float) {
        reset()
        if (enabled && position <= thumb + slop) startedOn = key
    }

    fun onDrag(delta: Float) {
        if (startedOn == null) return
        offset = (offset + delta).coerceIn(0f, travel)
    }

    fun onDragEnd() {
        val started = startedOn
        val reachedTheEnd = travel > 0f && travel >= minimumTravel && offset >= travel
        reset()
        if (started != null && reachedTheEnd) onConfirm(started.confirmKey)
    }

    fun onDragCancel() {
        reset()
    }

    /** The accessibility action: a completed slide in one step, on the current key. Returns whether it confirmed. */
    fun confirmByAccessibility(): Boolean {
        reset()
        if (enabled) onConfirm(key.confirmKey)
        return enabled
    }

    private fun reset() {
        startedOn = null
        offset = 0f
    }

    private data class SlideKey<K : Any>(val action: String, val systemId: Int, val confirmKey: K)
}
