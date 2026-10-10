package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@Composable
fun HudScreen(modifier: Modifier = Modifier, viewModel: HudViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HudContent(state = state, modifier = modifier)
}

@Composable
internal fun HudContent(state: HudUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(all = 24.dp),
        verticalArrangement = Arrangement.spacedBy(space = 12.dp, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.hud_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        when (state) {
            is HudUiState.Receiving -> {
                Text(
                    text = state.datagramCount.toString(),
                    style = GcsTheme.telemetryTypography.valueLarge,
                )
                Text(
                    text = stringResource(id = R.string.hud_datagrams_received),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(id = R.string.hud_listening_on_port, state.port),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is HudUiState.PortUnavailable -> Text(
                text = stringResource(id = R.string.hud_port_unavailable, state.port),
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
        }
        Text(
            text = stringResource(id = R.string.hud_disclaimer).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = GcsTheme.colors.caution,
        )
    }
}

@Preview
@Composable
private fun HudContentReceivingPreview() {
    GcsTheme {
        HudContent(state = HudUiState.Receiving(port = 14550, datagramCount = 1234))
    }
}

@Preview
@Composable
private fun HudContentPortUnavailablePreview() {
    GcsTheme {
        HudContent(state = HudUiState.PortUnavailable(port = 14550))
    }
}
