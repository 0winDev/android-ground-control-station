package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme

/**
 * The HUD in the [palette] in use, with the operator's switch between night and day ([onToggleTheme], REQ-030), laid
 * over the [map], which it tells the part of it its content covers.
 */
@Composable
fun HudScreen(
    palette: GcsPalette,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable (insets: PaddingValues) -> Unit = {},
    viewModel: HudViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HudContent(
        state = state,
        onAcknowledgeAlert = viewModel::onAlertAcknowledged,
        modifier = modifier,
        palette = palette,
        onToggleTheme = onToggleTheme,
        map = map,
    )
}

@Composable
internal fun HudContent(
    state: HudUiState,
    onAcknowledgeAlert: (occurrence: Long) -> Unit,
    modifier: Modifier = Modifier,
    palette: GcsPalette = GcsPalette.NIGHT,
    onToggleTheme: () -> Unit = {},
    map: @Composable (insets: PaddingValues) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = ContentSpacing)) {
                StatusBar(state = state, palette = palette, onToggleTheme = onToggleTheme)
            }
        }
        // One map whatever the state, so the camera never jumps when the link is lost or comes back.
        HudOverMap(
            map = map,
            modifier = Modifier.weight(1f),
            // Link lost changes the HUD at once, never animated in: it must not be missed or delayed.
            header = { if (state is HudUiState.LinkLost) EmergencyBanner(state = state) },
            start = { StartColumn(state = state) },
            side = { SideColumn(state = state, onAcknowledgeAlert = onAcknowledgeAlert) },
        )
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.padding(vertical = ChipSpacing), contentAlignment = Alignment.Center) {
                Disclaimer()
            }
        }
    }
}

/** The telemetry panel: live while the vehicle is heard, greyed as its last data once the link is lost (REQ-017). */
@Composable
private fun StartColumn(state: HudUiState) {
    when (state) {
        is HudUiState.Connected -> {
            TelemetryPanelContent(panel = state.telemetry)
            DatagramLine(port = state.port, datagramCount = state.datagramCount)
        }

        is HudUiState.LinkLost -> TelemetryPanelContent(panel = state.lastData, lostFor = state.sinceLastHeartbeat)

        else -> Unit
    }
}

@Composable
private fun SideColumn(state: HudUiState, onAcknowledgeAlert: (occurrence: Long) -> Unit) {
    when (state) {
        is HudUiState.Connected -> {
            // The vehicle's own alerts first: the banners below are about this device and the link.
            AlertBannerContent(banner = state.alert, onAcknowledge = onAcknowledgeAlert)
            UnstableLinkBanner(linkUnstable = state.linkUnstable)
            OtherVehiclesBanner(systemIds = state.otherVehicles)
        }

        is HudUiState.WaitingForVehicle -> {
            UnstableLinkBanner(linkUnstable = state.linkUnstable)
            MessageCard(state = state)
        }

        is HudUiState.LinkLost -> OtherVehiclesBanner(systemIds = state.otherVehicles)

        HudUiState.Starting -> Unit

        // Above the map with the banners, never in its middle, where a vehicle still heard is drawn.
        else -> MessageCard(state = state)
    }
}

/** Waiting for a vehicle, or a fault of the link, in words. */
@Composable
private fun MessageCard(state: HudUiState) {
    Surface(shape = ChipShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        MainContent(state = state, modifier = Modifier.padding(all = ScreenPadding))
    }
}

/** The link's traffic in one line under the panel, once a vehicle is heard. */
@Composable
private fun DatagramLine(port: Int?, datagramCount: Long) {
    val source = if (port == null) {
        stringResource(id = R.string.hud_playing_recording)
    } else {
        stringResource(id = R.string.hud_listening_on_port, port)
    }
    Surface(shape = ChipShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            text = stringResource(id = R.string.hud_datagram_line, datagramCount, source),
            modifier = Modifier.padding(horizontal = ChipPadding, vertical = ChipSpacing),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MainContent(state: HudUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(space = ContentSpacing, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is HudUiState.WaitingForVehicle -> {
                Text(
                    text = stringResource(id = R.string.hud_waiting_for_vehicle),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                DatagramCount(port = state.port, datagramCount = state.datagramCount)
            }

            is HudUiState.PortUnavailable -> Text(
                text = stringResource(id = R.string.hud_port_unavailable, state.port),
                style = MaterialTheme.typography.bodyLarge,
                color = GcsTheme.colors.caution,
                textAlign = TextAlign.Center,
            )

            is HudUiState.PortUnavailableRetrying -> Text(
                text = stringResource(id = R.string.hud_port_unavailable_retrying, state.port),
                style = MaterialTheme.typography.bodyLarge,
                color = GcsTheme.colors.caution,
                textAlign = TextAlign.Center,
            )

            HudUiState.LinkError -> Text(
                text = stringResource(id = R.string.hud_link_error),
                style = MaterialTheme.typography.bodyLarge,
                color = GcsTheme.colors.emergency,
                textAlign = TextAlign.Center,
            )

            HudUiState.ReplayUnreadable -> Text(
                text = stringResource(id = R.string.hud_replay_unreadable),
                style = MaterialTheme.typography.bodyLarge,
                color = GcsTheme.colors.emergency,
                textAlign = TextAlign.Center,
            )

            HudUiState.Starting, is HudUiState.Connected, is HudUiState.LinkLost -> Unit
        }
    }
}

@Composable
private fun DatagramCount(port: Int?, datagramCount: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = datagramCount.toString(), style = GcsTheme.telemetryTypography.valueLarge)
        Text(
            text = stringResource(id = R.string.hud_datagrams_received),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = if (port == null) {
                stringResource(id = R.string.hud_playing_recording)
            } else {
                stringResource(id = R.string.hud_listening_on_port, port)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
internal fun Disclaimer() {
    Text(
        text = stringResource(id = R.string.hud_disclaimer).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = GcsTheme.colors.caution,
    )
}

internal val ScreenPadding = 24.dp
internal val ContentSpacing = 12.dp
internal val ChipSpacing = 8.dp
internal val ChipPadding = 12.dp
internal val ChipShape = RoundedCornerShape(8.dp)
