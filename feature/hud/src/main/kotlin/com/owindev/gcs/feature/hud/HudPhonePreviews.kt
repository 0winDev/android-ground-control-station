package com.owindev.gcs.feature.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import kotlin.time.Duration.Companion.seconds

// The whole HUD on a phone, in both palettes (REQ-030): upright, short and on its side.

/** A phone's width: the theme switch must not cover or push away the alert banner or the battery chip. */
@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun HudConnectedPhoneNightPreview() {
    ConnectedPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun HudConnectedPhoneDayPreview() {
    ConnectedPreview(palette = GcsPalette.DAY)
}

/** A short phone: the map keeps its minimum under the alert and the telemetry scrolls under it. */
@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun HudConnectedShortPhoneNightPreview() {
    ConnectedPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun HudConnectedShortPhoneDayPreview() {
    ConnectedPreview(palette = GcsPalette.DAY)
}

/** A phone on its side: the alerts beside the telemetry, which scrolls in its column. */
@Preview(widthDp = 780, heightDp = 360)
@Composable
private fun HudConnectedPhoneLandscapeNightPreview() {
    ConnectedPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 780, heightDp = 360)
@Composable
private fun HudConnectedPhoneLandscapeDayPreview() {
    ConnectedPreview(palette = GcsPalette.DAY)
}

/**
 * The tallest top a short phone can have: a recorded LINK LOST under the REPLAY banner, with another vehicle heard. No
 * alert shows with it: LINK LOST outranks every alert (REQ-017).
 */
@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun HudLinkLostShortPhoneNightPreview() {
    LinkLostPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun HudLinkLostShortPhoneDayPreview() {
    LinkLostPreview(palette = GcsPalette.DAY)
}

@Composable
private fun LinkLostPreview(palette: GcsPalette) {
    GcsTheme(palette = palette) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HudContent(
                state = HudUiState.LinkLost(
                    lastKnown = PreviewArmed,
                    sinceLastHeartbeat = 7.seconds,
                    otherVehicles = listOf(2),
                    lastData = PreviewPanel,
                    recorded = true,
                ),
                onAcknowledgeAlert = {},
                palette = palette,
                replay = ReplayBanner(recording = "2026-10-24_09-47-44.tlog", status = ReplayStatus.ENDED),
            )
        }
    }
}
