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

// The whole HUD, connected, in both palettes (REQ-030), at a tablet's and a phone's width.

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

@Composable
private fun ConnectedPreview(palette: GcsPalette) {
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
