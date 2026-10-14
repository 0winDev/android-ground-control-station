package com.owindev.gcs.feature.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** Full-screen emergency for the active vehicle's link loss (REQ-017), with its last known state. */
@Composable
internal fun LinkLostContent(state: HudUiState.LinkLost, modifier: Modifier = Modifier) {
    val lastKnown = state.lastKnown
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GcsTheme.colors.emergencyContainer)
            .padding(all = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ContentSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Announced by TalkBack as soon as it appears, interrupting whatever was being read.
                .semantics { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(space = ContentSpacing, alignment = Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Decorative: the title right below says the same in words.
            Icon(
                painter = painterResource(id = DesignSystemR.drawable.ic_link_lost),
                contentDescription = null,
                tint = GcsTheme.colors.emergency,
                modifier = Modifier.size(EmergencyIconSize),
            )
            Text(
                text = stringResource(id = R.string.hud_link_lost_title).uppercase(),
                style = MaterialTheme.typography.displayLarge,
                color = GcsTheme.colors.onEmergencyContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(id = R.string.hud_link_lost_since, formatElapsed(state.sinceLastHeartbeat)),
                style = GcsTheme.telemetryTypography.valueLarge,
                color = GcsTheme.colors.onEmergencyContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(id = R.string.hud_last_known).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = GcsTheme.colors.stale,
            )
            Text(
                text = listOf(
                    stringResource(id = R.string.hud_system, lastKnown.systemId),
                    flightModeText(lastKnown.flightMode),
                    armedText(lastKnown.armed),
                ).joinToString(separator = " · ").uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = GcsTheme.colors.stale,
                textAlign = TextAlign.Center,
            )
            state.localFault?.let { fault -> LocalFaultText(fault = fault) }
        }
        OtherVehiclesBanner(systemIds = state.otherVehicles)
        Disclaimer()
    }
}

/** Says the fault is on this device, so the operator does not look for it on the vehicle. */
@Composable
private fun LocalFaultText(fault: LocalLinkFault) {
    Text(
        text = when (fault) {
            is LocalLinkFault.PortUnavailable -> stringResource(id = R.string.hud_local_fault_port, fault.port)
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
