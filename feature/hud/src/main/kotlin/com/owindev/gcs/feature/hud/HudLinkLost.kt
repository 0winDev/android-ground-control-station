package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme

/**
 * The active vehicle's link loss (REQ-017): an emergency banner across the top of the map, the largest and first
 * thing on screen, with the time without signal and the last known state; under it, the last data greyed with its
 * age and the map with the vehicle where it was last reported, greyed with the age of that position. Nothing in it
 * reads as current.
 */
@Composable
internal fun LinkLostContent(
    state: HudUiState.LinkLost,
    map: @Composable (insets: PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
) {
    HudOverMap(
        map = map,
        modifier = modifier,
        header = { EmergencyBanner(state = state) },
        start = { TelemetryPanelContent(panel = state.lastData, lostFor = state.sinceLastHeartbeat) },
        side = { OtherVehiclesBanner(systemIds = state.otherVehicles) },
    )
}

@Composable
private fun EmergencyBanner(state: HudUiState.LinkLost) {
    val lastKnown = state.lastKnown
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Announced by TalkBack as soon as it appears, interrupting whatever was being read.
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = ChipShape,
        color = GcsTheme.colors.emergencyContainer,
        border = BorderStroke(width = EmergencyBorder, color = GcsTheme.colors.emergency),
    ) {
        Row(
            modifier = Modifier.padding(all = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: the title right beside it says the same in words.
            Icon(
                painter = painterResource(id = DesignSystemR.drawable.ic_link_lost),
                contentDescription = null,
                tint = GcsTheme.colors.emergency,
                modifier = Modifier.size(EmergencyIconSize),
            )
            Column(verticalArrangement = Arrangement.spacedBy(ChipSpacing)) {
                Text(
                    text = stringResource(id = R.string.hud_link_lost_title).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = GcsTheme.colors.onEmergencyContainer,
                )
                Text(
                    text = stringResource(id = R.string.hud_link_lost_since, formatElapsed(state.sinceLastHeartbeat)),
                    style = GcsTheme.telemetryTypography.valueLarge,
                    color = GcsTheme.colors.onEmergencyContainer,
                )
                Text(
                    text = listOf(
                        stringResource(id = R.string.hud_last_known),
                        stringResource(id = R.string.hud_system, lastKnown.systemId),
                        flightModeText(lastKnown.flightMode),
                        armedText(lastKnown.armed),
                    ).joinToString(separator = " · ").uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = GcsTheme.colors.onEmergencyContainer,
                )
                state.localFault?.let { fault -> LocalFaultText(fault = fault) }
            }
        }
    }
}

/** Says the fault is on this device, so the operator does not look for it on the vehicle. */
@Composable
private fun LocalFaultText(fault: LocalLinkFault) {
    Text(
        text = when (fault) {
            is LocalLinkFault.PortUnavailable -> stringResource(id = R.string.hud_local_fault_port, fault.port)

            is LocalLinkFault.PortUnavailableRetrying ->
                stringResource(id = R.string.hud_local_fault_port_retrying, fault.port)

            LocalLinkFault.Failed -> stringResource(id = R.string.hud_local_fault_failed)

            LocalLinkFault.Unstable -> stringResource(id = R.string.hud_local_fault_unstable)
        },
        style = MaterialTheme.typography.titleLarge,
        color = GcsTheme.colors.onEmergencyContainer,
        textAlign = TextAlign.Center,
    )
}

/** Other system IDs on the link: shown so the operator knows, never merged into the active vehicle (REQ-020). */
@Composable
internal fun OtherVehiclesBanner(systemIds: List<Int>) {
    if (systemIds.isEmpty()) return
    CautionBanner(
        text = pluralStringResource(
            id = R.plurals.hud_other_vehicles,
            count = systemIds.size,
            systemIds.joinToString(separator = ", "),
        ),
    )
}

/** The link failed and is receiving again, but may fail again: the vehicle is still shown as usual. */
@Composable
internal fun UnstableLinkBanner(linkUnstable: Boolean) {
    if (linkUnstable) CautionBanner(text = stringResource(id = R.string.hud_link_unstable))
}

/** A caution with an icon and text, never color alone. */
@Composable
private fun CautionBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ChipShape,
        color = GcsTheme.colors.caution,
        contentColor = GcsTheme.colors.onCaution,
    ) {
        Row(
            modifier = Modifier.padding(all = ChipPadding),
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: the text next to it carries the warning.
            Icon(painter = painterResource(id = DesignSystemR.drawable.ic_warning), contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val EmergencyIconSize = 72.dp
private val EmergencyBorder = 2.dp
