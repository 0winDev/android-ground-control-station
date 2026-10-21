package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.vehicle.FlightMode

/**
 * System, link, mode and armed state of the active vehicle, as chips like the prototype's top bar, and the operator's
 * switch between night and day (REQ-030). The switch keeps its place on the left; the chips wrap onto another line
 * when the screen is too narrow, so none is pushed off it or hidden.
 */
@Composable
internal fun StatusBar(state: HudUiState, palette: GcsPalette, onToggleTheme: () -> Unit) {
    val noValue = stringResource(id = R.string.hud_no_value)
    val vehicle = (state as? HudUiState.Connected)?.vehicle
    val armed = vehicle?.armed == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        ThemeToggle(palette = palette, onToggle = onToggleTheme)
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(space = ChipSpacing, alignment = Alignment.End),
            verticalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            StatusChip(
                label = stringResource(id = R.string.hud_label_system),
                value = vehicle?.let { stringResource(id = R.string.hud_system, it.systemId) } ?: noValue,
            )
            LinkChip(state = state)
            GpsChip(reading = (state as? HudUiState.Connected)?.telemetry?.gps)
            BatteryChip(reading = (state as? HudUiState.Connected)?.telemetry?.battery)
            StatusChip(
                label = stringResource(id = R.string.hud_label_mode),
                value = vehicle?.let { flightModeText(it.flightMode) } ?: noValue,
            )
            StatusChip(
                label = stringResource(id = R.string.hud_label_state),
                value = vehicle?.let { armedText(it.armed) } ?: noValue,
                valueColor = if (armed) GcsTheme.colors.caution else MaterialTheme.colorScheme.onSurface,
                containerColor = if (armed) {
                    GcsTheme.colors.cautionContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
        }
    }
}

/**
 * Its icon shows the palette in use; its text and its description say what a tap does, so it never reads as a
 * state. One tap switches at once: changing palette is harmless and needs no confirmation.
 */
@Composable
private fun ThemeToggle(palette: GcsPalette, onToggle: () -> Unit) {
    val toDay = palette == GcsPalette.NIGHT
    val description =
        stringResource(id = if (toDay) R.string.hud_theme_switch_to_day else R.string.hud_theme_switch_to_night)
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .heightIn(min = GcsTheme.sizes.minTouchTarget)
            .semantics { contentDescription = description },
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ChipPadding, vertical = ChipPadding / 2),
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: the description says the action; the icon only shows the palette in use.
            Icon(
                painter = painterResource(
                    id = if (toDay) DesignSystemR.drawable.ic_dark_mode else DesignSystemR.drawable.ic_light_mode,
                ),
                contentDescription = null,
            )
            Text(
                text = stringResource(id = if (toDay) R.string.hud_theme_to_day else R.string.hud_theme_to_night)
                    .uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** The link chip agrees with the body below it: never WAITING while the link itself has failed. */
@Composable
private fun LinkChip(state: HudUiState) {
    val (value, color) = when (state) {
        is HudUiState.Connected -> stringResource(id = R.string.hud_link_connected) to GcsTheme.colors.nominal

        is HudUiState.WaitingForVehicle -> stringResource(id = R.string.hud_link_waiting) to GcsTheme.colors.advisory

        is HudUiState.PortUnavailable,
        is HudUiState.PortUnavailableRetrying,
        HudUiState.LinkError,
        HudUiState.ReplayUnreadable,
        -> stringResource(id = R.string.hud_link_failed) to GcsTheme.colors.emergency

        is HudUiState.LinkLost -> stringResource(id = R.string.hud_link_lost_chip) to GcsTheme.colors.emergency

        HudUiState.Starting -> stringResource(id = R.string.hud_no_value) to MaterialTheme.colorScheme.onSurface
    }
    StatusChip(label = stringResource(id = R.string.hud_label_link), value = value, valueColor = color)
}

/** Fix and satellites, as the prototype's GPS chip; greyed with its age once stale (REQ-029). */
@Composable
private fun GpsChip(reading: Reading<GpsStatus>?) {
    val noValue = stringResource(id = R.string.hud_no_value)
    val gps = reading?.value
    val value = gps?.let {
        stringResource(
            id = R.string.hud_gps_status,
            fixTypeText(it.fixType),
            it.satellitesVisible?.toString() ?: noValue,
        )
    } ?: noValue
    StatusChip(
        label = stringResource(id = R.string.hud_label_gps),
        value = withAge(value, reading),
        valueColor = valueColor(reading),
    )
}

/**
 * Remaining energy and voltage, as the prototype's battery chip; greyed with its age once stale (REQ-029). No
 * monitor and an unhealthy battery are said in words, the latter also in the emergency colour (#41).
 */
@Composable
private fun BatteryChip(reading: Reading<Battery>?) {
    val noValue = stringResource(id = R.string.hud_no_value)
    val state = batteryChipStateOf(reading?.value)
    val value = when (state) {
        BatteryChipState.NoReading -> noValue

        BatteryChipState.NoMonitor -> stringResource(id = R.string.hud_battery_no_monitor)

        is BatteryChipState.Measured -> {
            val measured = stringResource(
                id = R.string.hud_battery_status,
                state.remaining?.let { stringResource(id = R.string.hud_value_percent, it) } ?: noValue,
                state.voltage?.let { stringResource(id = R.string.hud_value_volts, it) } ?: noValue,
            )
            if (state.healthy) measured else stringResource(id = R.string.hud_battery_unhealthy, measured)
        }
    }
    val unhealthy = state is BatteryChipState.Measured && !state.healthy
    StatusChip(
        label = stringResource(id = R.string.hud_label_battery),
        value = withAge(value, reading),
        valueColor = if (unhealthy && reading?.stale != true) GcsTheme.colors.emergency else valueColor(reading),
        containerColor = if (unhealthy) {
            GcsTheme.colors.emergencyContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    )
}

/** A stale chip says so in words, not only in grey. */
@Composable
private fun withAge(value: String, reading: Reading<*>?): String =
    if (reading?.stale == true) "$value · ${ageText(reading.age)}" else value

@Composable
private fun StatusChip(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Surface(shape = ChipShape, color = containerColor) {
        Column(modifier = Modifier.padding(horizontal = ChipPadding, vertical = ChipPadding / 2)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value.uppercase(), style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

/** As the autopilot's documentation spells it (e.g. `AltHold`), like QGroundControl; never translated. */
@Composable
internal fun flightModeText(flightMode: FlightMode): String = when (flightMode) {
    is FlightMode.Named -> flightMode.name
    is FlightMode.Unknown -> stringResource(id = R.string.hud_mode_unknown, flightMode.number)
}

@Composable
internal fun armedText(armed: Boolean): String =
    stringResource(id = if (armed) R.string.hud_armed else R.string.hud_disarmed)

/** The fix, abbreviated; a type `GPS_FIX_TYPE` does not define shows its number. */
@Composable
internal fun fixTypeText(fixType: GpsFixType): String = when (fixType) {
    GpsFixType.NoGps -> stringResource(id = R.string.hud_fix_no_gps)
    GpsFixType.NoFix -> stringResource(id = R.string.hud_fix_none)
    GpsFixType.Fix2d -> stringResource(id = R.string.hud_fix_2d)
    GpsFixType.Fix3d -> stringResource(id = R.string.hud_fix_3d)
    GpsFixType.Dgps -> stringResource(id = R.string.hud_fix_dgps)
    GpsFixType.RtkFloat -> stringResource(id = R.string.hud_fix_rtk_float)
    GpsFixType.RtkFixed -> stringResource(id = R.string.hud_fix_rtk_fixed)
    GpsFixType.Static -> stringResource(id = R.string.hud_fix_static)
    GpsFixType.Ppp -> stringResource(id = R.string.hud_fix_ppp)
    is GpsFixType.Unknown -> stringResource(id = R.string.hud_fix_unknown, fixType.number)
}
