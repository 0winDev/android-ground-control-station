package com.owindev.gcs.feature.hud

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.alert.Alert
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import kotlinx.coroutines.delay

/**
 * The active vehicle's most urgent alert not yet acknowledged (REQ-028): its level in words and an icon, never
 * color alone, how many more are active, and an acknowledge button unless it is an emergency.
 */
@Composable
internal fun AlertBannerContent(banner: AlertBanner, onAcknowledge: (occurrence: Long) -> Unit) {
    when (banner) {
        AlertBanner.Hidden -> Unit

        is AlertBanner.Showing -> ShowingBanner(banner = banner, onAcknowledge = onAcknowledge)

        is AlertBanner.AllAcknowledged ->
            AcknowledgedBanner(count = banner.count, level = banner.level, recorded = banner.recorded)
    }
}

@Composable
private fun ShowingBanner(banner: AlertBanner.Showing, onAcknowledge: (occurrence: Long) -> Unit) {
    val style = styleOf(banner.alert.level)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = style.liveRegion },
        shape = ChipShape,
        color = style.container,
        contentColor = style.content,
        border = style.border?.let { BorderStroke(width = EmergencyBorder, color = it) },
    ) {
        Row(
            modifier = Modifier.padding(all = GcsTheme.spacing.chipPadding),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: the text next to it says the level.
            Icon(painter = painterResource(id = style.icon), contentDescription = null)
            if (banner.recorded) RecordedTag()
            Text(
                text = stringResource(
                    id = R.string.hud_alert_banner,
                    stringResource(id = style.label),
                    banner.alert.message(banner.batteryRemaining),
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            banner.othersLevel?.let { OthersCount(others = banner.others, level = it) }
            if (banner.acknowledgeable) {
                // Enabled once this alert has been on screen for the guard, as the ViewModel requires.
                var enabled by remember(banner.occurrence) { mutableStateOf(false) }
                LaunchedEffect(banner.occurrence) {
                    delay(ACKNOWLEDGE_GUARD)
                    enabled = true
                }
                // The banner's own content colour, enabled or not: inside the button LocalContentColor is the button's
                // primary, which all but vanished on the caution amber (1.1:1). The 1 s guard needs no fading.
                OutlinedButton(
                    onClick = { onAcknowledge(banner.occurrence) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = style.content,
                        disabledContentColor = style.content,
                    ),
                    border = BorderStroke(width = 1.dp, color = style.content),
                ) {
                    Text(text = stringResource(id = R.string.hud_alert_acknowledge).uppercase())
                }
            }
        }
    }
}

/** How many more alerts are active, in the style of the most urgent of them, so it is never played down. */
@Composable
private fun OthersCount(others: Int, level: AlertLevel) {
    val style = styleOf(level)
    val description = pluralStringResource(
        id = R.plurals.hud_alert_others_description,
        count = others,
        others,
        stringResource(id = style.label),
    )
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = ChipShape,
        color = style.container,
        contentColor = style.content,
        border = style.border?.let { BorderStroke(width = EmergencyBorder, color = it) },
    ) {
        Row(
            modifier = Modifier.padding(all = GcsTheme.spacing.chip),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(id = style.icon), contentDescription = null)
            Text(
                text = stringResource(id = R.string.hud_alert_others, others),
                style = GcsTheme.telemetryTypography.value,
            )
        }
    }
}

/**
 * Every active alert is acknowledged: they are still active, so the HUD still says so, at the level of the most
 * urgent of them.
 */
@Composable
private fun AcknowledgedBanner(count: Int, level: AlertLevel, recorded: Boolean) {
    val style = styleOf(level)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ChipShape,
        color = style.container,
        contentColor = style.content,
        border = style.border?.let { BorderStroke(width = EmergencyBorder, color = it) },
    ) {
        Row(
            modifier = Modifier.padding(all = GcsTheme.spacing.chipPadding),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(id = DesignSystemR.drawable.ic_check_circle), contentDescription = null)
            if (recorded) RecordedTag()
            Text(
                text = stringResource(
                    id = R.string.hud_alert_banner,
                    stringResource(id = style.label),
                    pluralStringResource(id = R.plurals.hud_alerts_acknowledged, count = count, count),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Alert.message(batteryRemaining: Int?): String = when (this) {
    Alert.BatteryCritical ->
        batteryRemaining
            ?.let { stringResource(id = R.string.hud_alert_battery_critical_percent, it) }
            ?: stringResource(id = R.string.hud_alert_battery_critical)

    Alert.BatteryLow ->
        batteryRemaining
            ?.let { stringResource(id = R.string.hud_alert_battery_low_percent, it) }
            ?: stringResource(id = R.string.hud_alert_battery_low)

    Alert.BatteryUnhealthy -> stringResource(id = R.string.hud_alert_battery_unhealthy)

    Alert.BatteryUnknown -> stringResource(id = R.string.hud_alert_battery_unknown)

    Alert.GpsFixLost -> stringResource(id = R.string.hud_alert_gps_fix_lost)

    is Alert.TelemetryStale -> stringResource(id = staleMessageOf(kind))
}

@StringRes
private fun staleMessageOf(kind: TelemetryKind): Int = when (kind) {
    TelemetryKind.POSITION -> R.string.hud_alert_stale_position
    TelemetryKind.ATTITUDE -> R.string.hud_alert_stale_attitude
    TelemetryKind.AIR_DATA -> R.string.hud_alert_stale_air_data
    TelemetryKind.BATTERY -> R.string.hud_alert_stale_battery
    TelemetryKind.GPS -> R.string.hud_alert_stale_gps
}

/** How a level looks: an emergency is announced at once and outlined, like the link-lost screen. */
private data class LevelStyle(
    val container: Color,
    val content: Color,
    val border: Color?,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val liveRegion: LiveRegionMode,
)

@Composable
private fun styleOf(level: AlertLevel): LevelStyle = when (level) {
    AlertLevel.ADVISORY -> LevelStyle(
        container = GcsTheme.colors.advisoryContainer,
        content = GcsTheme.colors.advisory,
        border = null,
        icon = DesignSystemR.drawable.ic_info,
        label = R.string.hud_alert_level_advisory,
        liveRegion = LiveRegionMode.Polite,
    )

    AlertLevel.CAUTION -> LevelStyle(
        container = GcsTheme.colors.caution,
        content = GcsTheme.colors.onCaution,
        border = null,
        icon = DesignSystemR.drawable.ic_warning,
        label = R.string.hud_alert_level_caution,
        liveRegion = LiveRegionMode.Polite,
    )

    AlertLevel.EMERGENCY -> LevelStyle(
        container = GcsTheme.colors.emergencyContainer,
        content = GcsTheme.colors.onEmergencyContainer,
        border = GcsTheme.colors.emergency,
        icon = DesignSystemR.drawable.ic_error,
        label = R.string.hud_alert_level_emergency,
        liveRegion = LiveRegionMode.Assertive,
    )
}

private val EmergencyBorder = 2.dp

@Preview(widthDp = 1280)
@Composable
private fun AlertBannerPreview() {
    GcsTheme {
        Column(verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content)) {
            AlertBannerContent(
                banner = AlertBanner.Showing(
                    alert = Alert.BatteryCritical,
                    occurrence = 3,
                    acknowledgeable = false,
                    others = 2,
                    othersLevel = AlertLevel.CAUTION,
                    batteryRemaining = 9,
                ),
                onAcknowledge = {},
            )
            AlertBannerContent(
                banner = AlertBanner.Showing(
                    alert = Alert.BatteryLow,
                    occurrence = 2,
                    acknowledgeable = true,
                    others = 1,
                    othersLevel = AlertLevel.ADVISORY,
                    batteryRemaining = 18,
                ),
                onAcknowledge = {},
            )
            AlertBannerContent(
                banner = AlertBanner.Showing(
                    alert = Alert.TelemetryStale(TelemetryKind.ATTITUDE),
                    occurrence = 1,
                    acknowledgeable = true,
                    others = 1,
                    othersLevel = AlertLevel.CAUTION,
                ),
                onAcknowledge = {},
            )
            AlertBannerContent(
                banner = AlertBanner.AllAcknowledged(count = 3, level = AlertLevel.CAUTION),
                onAcknowledge = {},
            )
        }
    }
}
