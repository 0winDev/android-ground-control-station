package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.geo.Mgrs
import com.owindev.gcs.core.domain.telemetry.AirData
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The active vehicle's telemetry, as the prototype's left panel (REQ-029). A value the vehicle does not know, or
 * sent out of range, is shown as no value; a stale one is greyed and shows its age, never colour alone.
 */
@Composable
internal fun TelemetryPanelContent(panel: TelemetryPanel, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(PanelWidth),
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(all = ContentSpacing),
            verticalArrangement = Arrangement.spacedBy(ContentSpacing),
        ) {
            AttitudeLine(reading = panel.attitude)
            Row(horizontalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                ValueCell(
                    label = stringResource(id = R.string.hud_label_altitude),
                    value = panel.position?.value?.position?.let { metres(it.altitudeAboveHome) },
                    reading = panel.position,
                    modifier = Modifier.weight(1f),
                )
                ValueCell(
                    label = stringResource(id = R.string.hud_label_ground_speed),
                    value = panel.airData?.value?.groundspeed?.let { speed(it) },
                    reading = panel.airData,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                ValueCell(
                    label = stringResource(id = R.string.hud_label_heading),
                    value = panel.position?.value?.position?.heading?.let { degrees(it) },
                    reading = panel.position,
                    modifier = Modifier.weight(1f),
                )
                ValueCell(
                    label = stringResource(id = R.string.hud_label_vertical_speed),
                    value = panel.airData?.value?.let(AirData::climbRate)?.let { climbRate(it) },
                    reading = panel.airData,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            PositionLines(reading = panel.position)
        }
    }
}

/** Roll and pitch together: a horizon needs both, so with either one missing neither is shown (#49). */
@Composable
private fun AttitudeLine(reading: Reading<Attitude>?) {
    val roll = reading?.value?.roll
    val pitch = reading?.value?.pitch
    val text = if (roll != null && pitch != null) {
        stringResource(id = R.string.hud_attitude, signedDegrees(roll), signedDegrees(pitch))
    } else {
        stringResource(id = R.string.hud_attitude_not_available)
    }
    Column {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = valueColor(reading),
        )
        AgeText(reading = reading)
    }
}

@Composable
private fun PositionLines(reading: Reading<PositionReading>?) {
    val position = reading?.value?.position
    // Not verified is shown like stale, and said in words: it may be 0°, 0° or a frozen position.
    val notVerified = reading != null && !reading.value.verified
    val color = if (notVerified) GcsTheme.colors.stale else valueColor(reading)
    Column(verticalArrangement = Arrangement.spacedBy(ValueSpacing)) {
        Label(text = stringResource(id = R.string.hud_label_position))
        // Read before the digits, which stay readable: the last known position helps find the vehicle.
        if (notVerified) {
            Text(
                text = stringResource(id = R.string.hud_position_not_verified),
                style = MaterialTheme.typography.bodySmall,
                color = GcsTheme.colors.caution,
            )
        }
        Text(
            text = position?.let { coordinates(it) } ?: stringResource(id = R.string.hud_no_value),
            style = GcsTheme.telemetryTypography.coordinate,
            color = color,
        )
        Label(text = stringResource(id = R.string.hud_label_mgrs))
        Text(
            text = reading?.value?.mgrs?.let { mgrsText(it) } ?: stringResource(id = R.string.hud_no_value),
            style = GcsTheme.telemetryTypography.value,
            color = color,
        )
        AgeText(reading = reading)
    }
}

@Composable
private fun ValueCell(label: String, value: String?, reading: Reading<*>?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ValueSpacing)) {
        Label(text = label)
        Text(
            text = value ?: stringResource(id = R.string.hud_no_value),
            style = GcsTheme.telemetryTypography.valueLarge,
            color = valueColor(reading),
        )
        AgeText(reading = reading)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The age of a stale reading, in words next to the grey, so staleness is never told by colour alone. */
@Composable
private fun AgeText(reading: Reading<*>?) {
    if (reading?.stale == true) {
        Text(
            text = ageText(reading.age),
            style = MaterialTheme.typography.bodySmall,
            color = GcsTheme.colors.stale,
        )
    }
}

private val PanelWidth = 320.dp
private val ValueSpacing = 2.dp

private val PreviewPosition = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = 42.3,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = 169.0,
)

private val PreviewPanel = TelemetryPanel(
    position = Reading(
        PositionReading(
            PreviewPosition,
            Mgrs.Grid(zone = 55, band = 'H', square = "FA", easting = 96720, northing = 84509),
            verified = true,
        ),
        stale = false,
        age = Duration.ZERO,
    ),
    attitude = Reading(Attitude(roll = -6.0, pitch = 3.0, yaw = 169.0), stale = false, age = Duration.ZERO),
    airData = Reading(
        AirData(airspeed = 8.4, groundspeed = 8.4, climbRate = 0.2, throttle = 45),
        stale = false,
        age = Duration.ZERO,
    ),
)

@Preview
@Composable
private fun TelemetryPanelFreshPreview() {
    GcsTheme { TelemetryPanelContent(panel = PreviewPanel) }
}

@Preview
@Composable
private fun TelemetryPanelStalePreview() {
    GcsTheme {
        TelemetryPanelContent(
            panel = PreviewPanel.copy(
                position = PreviewPanel.position?.copy(stale = true, age = 4.seconds),
                attitude = PreviewPanel.attitude?.copy(stale = true, age = 2.seconds),
            ),
        )
    }
}

/** Values the vehicle does not know or sent out of range (#49), and an MGRS outside UTM. */
@Preview
@Composable
private fun TelemetryPanelMissingPreview() {
    GcsTheme {
        TelemetryPanelContent(
            panel = TelemetryPanel(
                position = Reading(
                    PositionReading(
                        PreviewPosition.copy(latitude = 85.0, heading = null),
                        Mgrs.NotAvailable,
                        verified = false,
                    ),
                    stale = false,
                    age = Duration.ZERO,
                ),
                attitude = Reading(Attitude(roll = null, pitch = 3.0, yaw = null), stale = false, age = Duration.ZERO),
                airData = Reading(
                    AirData(airspeed = null, groundspeed = null, climbRate = null, throttle = null),
                    stale = false,
                    age = Duration.ZERO,
                ),
            ),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun HudConnectedWithTelemetryPreview() {
    GcsTheme {
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
                telemetry = PreviewPanel,
                alert = AlertBanner.Showing(
                    alert = Alert.BatteryLow,
                    occurrence = 1,
                    acknowledgeable = true,
                    others = 1,
                    batteryRemaining = 18,
                ),
            ),
            onAcknowledgeAlert = {},
        )
    }
}
