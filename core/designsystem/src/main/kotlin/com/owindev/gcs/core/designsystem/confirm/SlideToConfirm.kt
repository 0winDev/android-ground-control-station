package com.owindev.gcs.core.designsystem.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.R
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import kotlin.math.roundToInt

/**
 * The confirmation of a dangerous command (REQ-050): "Slide to [action] · SYS [systemId]". [onConfirm] runs once with
 * the [confirmKey] the slide started on, when the thumb, grabbed where it is, is released at the end of a track
 * allowing at least 160 dp of travel; a slide whose action, system ID or key changes, that is disabled or whose
 * gesture is cancelled meanwhile is dropped. Disabled while [disabledReason] is set, which it shows. TalkBack confirms
 * through a custom action with the same label, on the current key; a double tap does nothing. A second finger may
 * carry a slide on after the first lifts: it still has to reach the end.
 *
 * [confirmKey] is a data value that changes only on what drops a slide (REQ-050: the active vehicle, the link, the
 * replay state, the route), never with ages or clocks. A change reaches the control one recomposition late, so the
 * sender compares the key it gets with its current one under its send lock and sends nothing when they differ.
 */
@Composable
fun <K : Any> SlideToConfirm(
    action: String,
    systemId: Int,
    confirmKey: K,
    onConfirm: (K) -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
) {
    val enabled = disabledReason == null
    val label = stringResource(R.string.slide_to_confirm, action, systemId)
    val state = remember { SlideToConfirmState(action, systemId, confirmKey, enabled, onConfirm) }
    SideEffect { state.update(action, systemId, confirmKey, enabled, onConfirm) }
    val thumbSize = GcsTheme.sizes.gloveButton
    val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }
    val minimumTravelPx = with(LocalDensity.current) { MinimumTravel.toPx() }
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = MaterialTheme.colorScheme
    val gcsColors = GcsTheme.colors
    val progressColor = gcsColors.caution.copy(alpha = PROGRESS_ALPHA)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                if (disabledReason != null) {
                    stateDescription = disabledReason
                    disabled()
                } else {
                    customActions = listOf(CustomAccessibilityAction(label, state::confirmByAccessibility))
                }
            },
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbSize)
                .clip(ConfirmShape)
                .background(if (enabled) gcsColors.cautionContainer else colors.surfaceContainerHigh)
                .drawBehind { if (enabled) drawProgress(progressColor, state.offset + thumbPx / 2, mirrored) }
                .border(TrackBorder, if (enabled) gcsColors.caution else colors.outline, ConfirmShape)
                .onSizeChanged { state.onTrackMeasured(it.width.toFloat(), thumbPx, minimumTravelPx) }
                .pointerInput(state, mirrored) { detectSlide(state, mirrored) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.uppercase(),
                modifier = Modifier.padding(start = thumbSize + GcsTheme.spacing.chip, end = GcsTheme.spacing.content),
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = LABEL_MAX_LINES,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
            val thumbColor = if (enabled) gcsColors.caution else colors.outline
            val chevronColor = if (enabled) gcsColors.onCaution else colors.surface
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(state.offset.roundToInt(), 0) }
                    .size(thumbSize)
                    .clip(ConfirmShape)
                    .background(thumbColor)
                    .drawBehind { drawChevron(chevronColor, mirrored) },
            )
        }
        if (disabledReason != null) {
            Text(
                text = disabledReason,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// `draggable` reports a cancelled gesture as a stop (`onDragStopped` with zero velocity), so it could confirm a slide
// the system cancelled: this detector calls onDragCancel apart, and a restart of the handler calls neither.
private suspend fun PointerInputScope.detectSlide(state: SlideToConfirmState<*>, mirrored: Boolean) {
    val direction = if (mirrored) -1f else 1f
    try {
        detectHorizontalDragGestures(
            onDragStart = { position ->
                val fromTrackStart = if (mirrored) size.width - position.x else position.x
                state.onDragStart(fromTrackStart, viewConfiguration.touchSlop)
            },
            onDragEnd = state::onDragEnd,
            onDragCancel = state::onDragCancel,
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount * direction)
            },
        )
    } finally {
        state.onDragCancel()
    }
}

private fun DrawScope.drawProgress(color: Color, width: Float, mirrored: Boolean) {
    val filled = width.coerceAtMost(size.width)
    drawRect(
        color = color,
        topLeft = Offset(if (mirrored) size.width - filled else 0f, 0f),
        size = Size(filled, size.height),
    )
}

private fun DrawScope.drawChevron(color: Color, mirrored: Boolean) {
    val half = size.minDimension * CHEVRON_HALF_HEIGHT
    val path = Path().apply {
        moveTo(center.x - half / 2, center.y - half)
        lineTo(center.x + half / 2, center.y)
        lineTo(center.x - half / 2, center.y + half)
    }
    scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f, pivot = center) {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = ChevronStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// 160 dp is one inch (about 25 mm) on every screen: far longer than a brush or a fingertip's jitter with a glove,
// while a 360 dp phone still leaves 264 dp of travel past the 72 dp thumb inside 12 dp margins.
private val MinimumTravel = 160.dp

private const val LABEL_MAX_LINES = 3
private const val PROGRESS_ALPHA = 0.35f
private val ConfirmShape = RoundedCornerShape(10.dp)
private const val CHEVRON_HALF_HEIGHT = 0.18f
private val TrackBorder = 1.dp
private val ChevronStroke = 3.dp

@Preview(widthDp = 360)
@Composable
private fun SlideToConfirmNightPreview() {
    SlideToConfirmPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360)
@Composable
private fun SlideToConfirmDayPreview() {
    SlideToConfirmPreview(palette = GcsPalette.DAY)
}

@Composable
private fun SlideToConfirmPreview(palette: GcsPalette) {
    GcsTheme(palette = palette) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(GcsTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
        ) {
            SlideToConfirm(action = "RTL", systemId = 1, confirmKey = Unit, onConfirm = {})
            SlideToConfirm(
                action = "take off",
                systemId = 1,
                confirmKey = Unit,
                onConfirm = {},
                disabledReason = "Pre-flight check red: no 3D GPS fix",
            )
        }
    }
}
