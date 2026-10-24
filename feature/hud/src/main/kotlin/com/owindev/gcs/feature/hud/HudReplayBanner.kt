package com.owindev.gcs.feature.hud

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.layout.CompactWidth
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.designsystem.time.elapsedText

/**
 * The replay banner (REQ-037): across the whole screen above the alerts and the link-lost emergency, so a replay never
 * looks like a live flight. It says REPLAY in words, the recording, what the replay is doing and the recorded time it
 * is skipping, if any. Pause and resume share one place and Stop keeps its own, apart, so no control moves under a
 * finger (lesson #56).
 */
@Composable
internal fun ReplayBannerContent(banner: ReplayBanner, controls: ReplayControls, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = GcsTheme.colors.advisoryContainer,
        border = BorderStroke(width = ReplayBorder, color = GcsTheme.colors.advisory),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = GcsTheme.spacing.screen, vertical = GcsTheme.spacing.chip),
        ) {
            // At a phone's width the words need the room: the buttons show their icon only.
            val iconOnly = maxWidth < CompactWidth
            Row(
                horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReplayTitle(banner = banner, phone = iconOnly, modifier = Modifier.weight(1f))
                ReplayButtons(banner = banner, controls = controls, iconOnly = iconOnly)
            }
        }
    }
}

@Composable
private fun ReplayTitle(banner: ReplayBanner, phone: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative: the title beside it says the same in words. A phone needs its room for the words.
        if (!phone) {
            Icon(
                painter = painterResource(id = DesignSystemR.drawable.ic_info),
                contentDescription = null,
                tint = GcsTheme.colors.advisory,
            )
        }
        Column {
            Text(
                text = stringResource(id = R.string.hud_replay_title).uppercase(),
                style = if (phone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = GcsTheme.colors.advisory,
            )
            Text(
                text = listOfNotNull(
                    stringResource(id = statusText(banner.status)),
                    banner.recording,
                    banner.skipped?.let { stringResource(id = R.string.hud_replay_skipped, elapsedText(it)) },
                ).joinToString(separator = " · "),
                style = if (phone) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Pause or resume in one place, then Stop apart, laid out by the row they are called in. */
@Composable
private fun RowScope.ReplayButtons(banner: ReplayBanner, controls: ReplayControls, iconOnly: Boolean) {
    when (banner.status) {
        ReplayStatus.PLAYING -> ReplayButton(
            R.string.hud_replay_pause,
            DesignSystemR.drawable.ic_pause,
            iconOnly,
            controls.onPause,
        )

        ReplayStatus.PAUSED -> ReplayButton(
            R.string.hud_replay_resume,
            DesignSystemR.drawable.ic_play,
            iconOnly,
            controls.onResume,
        )

        // The same width kept empty, so Stop never moves.
        ReplayStatus.ENDED, ReplayStatus.UNREADABLE ->
            Spacer(modifier = Modifier.width(if (iconOnly) GcsTheme.sizes.minTouchTarget else ReplayButtonWidth))
    }
    Spacer(modifier = Modifier.width(if (iconOnly) GcsTheme.spacing.chip else GcsTheme.spacing.screen))
    ReplayButton(R.string.hud_replay_stop, DesignSystemR.drawable.ic_stop, iconOnly, controls.onStop)
}

/** Its label in words, or as an icon that still says it to TalkBack, with a 48 dp touch target either way (REQ-007). */
@Composable
private fun ReplayButton(@StringRes label: Int, @DrawableRes icon: Int, iconOnly: Boolean, onClick: () -> Unit) {
    val border = BorderStroke(width = 1.dp, color = GcsTheme.colors.advisory)
    if (iconOnly) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = Modifier.size(GcsTheme.sizes.minTouchTarget),
            border = border,
        ) {
            Icon(painter = painterResource(id = icon), contentDescription = stringResource(id = label))
        }
        return
    }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = GcsTheme.sizes.button)
            .width(ReplayButtonWidth),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = border,
    ) {
        Text(text = stringResource(id = label).uppercase())
    }
}

/** Says in words that what it sits on came from a recording, an emergency included (REQ-037). */
@Composable
internal fun RecordedTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = ChipShape,
        color = GcsTheme.colors.advisoryContainer,
        border = BorderStroke(width = 1.dp, color = GcsTheme.colors.advisory),
    ) {
        Text(
            text = stringResource(id = R.string.hud_recorded).uppercase(),
            modifier = Modifier.padding(horizontal = GcsTheme.spacing.chip, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            color = GcsTheme.colors.advisory,
        )
    }
}

@StringRes
private fun statusText(status: ReplayStatus): Int = when (status) {
    ReplayStatus.PLAYING -> R.string.hud_replay_playing
    ReplayStatus.PAUSED -> R.string.hud_replay_paused
    ReplayStatus.ENDED -> R.string.hud_replay_ended
    ReplayStatus.UNREADABLE -> R.string.hud_replay_unreadable_status
}

private val ReplayButtonWidth = 140.dp
private val ReplayBorder = 2.dp
