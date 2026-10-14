package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration.Companion.seconds

@Composable
fun HudScreen(modifier: Modifier = Modifier, viewModel: HudViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HudContent(state = state, modifier = modifier)
}

@Composable
internal fun HudContent(state: HudUiState, modifier: Modifier = Modifier) {
    // Link lost replaces the whole HUD at once, never animated in: it must not be missed or delayed.
    when (state) {
        is HudUiState.LinkLost -> LinkLostContent(state = state, modifier = modifier)
        else -> LinkContent(state = state, modifier = modifier)
    }
}

@Composable
private fun LinkContent(state: HudUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(all = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ContentSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBar(state = state)
        when (state) {
            is HudUiState.Connected -> {
                UnstableLinkBanner(linkUnstable = state.linkUnstable)
                OtherVehiclesBanner(systemIds = state.otherVehicles)
            }

            is HudUiState.WaitingForVehicle -> UnstableLinkBanner(linkUnstable = state.linkUnstable)

            else -> Unit
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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

                is HudUiState.Connected -> DatagramCount(port = state.port, datagramCount = state.datagramCount)

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

                HudUiState.Starting, is HudUiState.LinkLost -> Unit
            }
        }
        Disclaimer()
    }
}

@Composable
private fun DatagramCount(port: Int, datagramCount: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = datagramCount.toString(), style = GcsTheme.telemetryTypography.valueLarge)
        Text(
            text = stringResource(id = R.string.hud_datagrams_received),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(id = R.string.hud_listening_on_port, port),
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

private val PreviewArmed = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Guided"), armed = true)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudContentConnectedPreview() {
    GcsTheme {
        HudContent(
            state = HudUiState.Connected(
                vehicle = PreviewArmed,
                port = 14550,
                datagramCount = 1234,
                otherVehicles = listOf(2),
            ),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudContentConnectedUnstablePreview() {
    GcsTheme {
        HudContent(
            state = HudUiState.Connected(
                vehicle = PreviewArmed,
                port = 14550,
                datagramCount = 1234,
                otherVehicles = emptyList(),
                linkUnstable = true,
            ),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudContentWaitingPreview() {
    GcsTheme {
        HudContent(state = HudUiState.WaitingForVehicle(port = 14550, datagramCount = 0))
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudContentLinkLostPreview() {
    GcsTheme {
        HudContent(
            state = HudUiState.LinkLost(
                lastKnown = PreviewArmed,
                sinceLastHeartbeat = 7.seconds,
                otherVehicles = emptyList(),
            ),
        )
    }
}
