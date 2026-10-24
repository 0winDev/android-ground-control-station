package com.owindev.gcs.feature.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The whole HUD in both palettes (REQ-030) at a tablet's size; a phone's are in HudPhonePreviews.

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudConnectedWithTelemetryPreview() {
    ConnectedPreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudConnectedDayPreview() {
    ConnectedPreview(palette = GcsPalette.DAY)
}

@Composable
internal fun ConnectedPreview(palette: GcsPalette) {
    GcsTheme(palette = palette) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HudContent(
                state = HudUiState.Connected(
                    vehicle = VehicleStatus(
                        systemId = 1,
                        flightMode = FlightMode.Named("Guided"),
                        armed = true,
                    ),
                    port = 14550,
                    datagramCount = 1234,
                    otherVehicles = emptyList(),
                    telemetry = PreviewPanel.copy(battery = Reading(PreviewBattery, stale = false, Duration.ZERO)),
                    alert = AlertBanner.Showing(
                        alert = Alert.BatteryLow,
                        occurrence = 1,
                        acknowledgeable = true,
                        others = 1,
                        othersLevel = AlertLevel.ADVISORY,
                        batteryRemaining = 18,
                    ),
                ),
                onAcknowledgeAlert = {},
                palette = palette,
            )
        }
    }
}

private val PreviewBattery = Battery(voltage = 11.1, current = 12.4, remaining = 18, monitored = true, healthy = true)

internal val PreviewArmed = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Guided"), armed = true)

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
            onAcknowledgeAlert = {},
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
            onAcknowledgeAlert = {},
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudContentWaitingPreview() {
    GcsTheme {
        HudContent(state = HudUiState.WaitingForVehicle(port = 14550, datagramCount = 0), onAcknowledgeAlert = {})
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
            onAcknowledgeAlert = {},
        )
    }
}
