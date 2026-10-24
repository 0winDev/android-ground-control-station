package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.vehicle.FlightMode

/** The vehicle's chips; they wrap onto another line when the width runs out, so none is pushed off or hidden. */
@Composable
internal fun StatusChips(state: HudUiState, alignment: Alignment.Horizontal, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(space = GcsTheme.spacing.chip, alignment = alignment),
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
    ) {
        StatusChipItems(state = state)
    }
}

/**
 * The chips alone, laid out by the row they are called in. [compact] chips, for a phone, show a short value without
 * its label, as the prototype's phone bar; their description still says the label and the whole value (voltage,
 * satellites), and the link's state is always in words.
 */
@Composable
internal fun FlowRowScope.StatusChipItems(state: HudUiState, compact: Boolean = false) {
    val noValue = stringResource(id = R.string.hud_no_value)
    val lost = state is HudUiState.LinkLost
    val vehicle = (state as? HudUiState.Connected)?.vehicle ?: (state as? HudUiState.LinkLost)?.lastKnown
    val telemetry = (state as? HudUiState.Connected)?.telemetry ?: (state as? HudUiState.LinkLost)?.lastData
    val armed = vehicle?.armed == true && !lost
    val plainColor = if (lost) GcsTheme.colors.stale else MaterialTheme.colorScheme.onSurface
    StatusChip(
        label = stringResource(id = R.string.hud_label_system),
        value = vehicle?.let { stringResource(id = R.string.hud_system, it.systemId) } ?: noValue,
        valueColor = plainColor,
        compact = compact,
    )
    LinkChip(state = state, compact = compact)
    GpsChip(reading = telemetry?.gps, lost = lost, compact = compact)
    BatteryChip(reading = telemetry?.battery, lost = lost, compact = compact)
    StatusChip(
        label = stringResource(id = R.string.hud_label_mode),
        value = vehicle?.let { flightModeText(it.flightMode) } ?: noValue,
        valueColor = plainColor,
        compact = compact,
    )
    StatusChip(
        label = stringResource(id = R.string.hud_label_state),
        value = vehicle?.let { armedText(it.armed) } ?: noValue,
        valueColor = if (armed) GcsTheme.colors.caution else plainColor,
        containerColor = if (armed) {
            GcsTheme.colors.cautionContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        compact = compact,
    )
}

/** The link chip, as [linkChipStateOf] decides it; a replay in the replay banner's colour. */
@Composable
private fun LinkChip(state: HudUiState, compact: Boolean) {
    val (value, color) = when (linkChipStateOf(state)) {
        LinkChipState.CONNECTED -> stringResource(id = R.string.hud_link_connected) to GcsTheme.colors.nominal
        LinkChipState.REPLAY -> stringResource(id = R.string.hud_link_replay) to GcsTheme.colors.advisory
        LinkChipState.WAITING -> stringResource(id = R.string.hud_link_waiting) to GcsTheme.colors.advisory
        LinkChipState.FAILED -> stringResource(id = R.string.hud_link_failed) to GcsTheme.colors.emergency
        LinkChipState.LOST -> stringResource(id = R.string.hud_link_lost_chip) to GcsTheme.colors.emergency
        LinkChipState.NONE -> stringResource(id = R.string.hud_no_value) to MaterialTheme.colorScheme.onSurface
    }
    StatusChip(
        label = stringResource(id = R.string.hud_label_link),
        value = value,
        valueColor = color,
        compact = compact,
    )
}

/** Fix and satellites, as the prototype's GPS chip; greyed with its age once stale (REQ-029). */
@Composable
private fun GpsChip(reading: Reading<GpsStatus>?, lost: Boolean, compact: Boolean) {
    val noValue = stringResource(id = R.string.hud_no_value)
    val gps = reading?.value
    val satellites = gps?.satellitesVisible?.toString() ?: noValue
    val value =
        gps?.let { stringResource(id = R.string.hud_gps_status, fixTypeText(it.fixType), satellites) } ?: noValue
    val short = gps?.let {
        stringResource(id = R.string.hud_gps_status_short, fixTypeShortText(it.fixType), satellites)
    }
    StatusChip(
        label = stringResource(id = R.string.hud_label_gps),
        value = withAge(value, reading),
        valueColor = valueColor(reading, lost),
        compact = compact,
        shortValue = withAge(short ?: noValue, reading),
    )
}

/**
 * Remaining energy and voltage, as the prototype's battery chip; greyed with its age once stale (REQ-029). No
 * monitor and an unhealthy battery are said in words, the latter also in the emergency colour (#41).
 */
@Composable
private fun BatteryChip(reading: Reading<Battery>?, lost: Boolean, compact: Boolean) {
    val state = batteryChipStateOf(reading?.value)
    val value = batteryText(state)
    val short = (state as? BatteryChipState.Measured)?.let { batteryShortText(it) } ?: value
    // A battery last reported unhealthy keeps saying so in words; once the link is lost it is greyed like the rest.
    val unhealthy = state is BatteryChipState.Measured && !state.healthy && !lost
    StatusChip(
        label = stringResource(id = R.string.hud_label_battery),
        value = withAge(value, reading),
        valueColor = if (unhealthy && reading?.stale != true) GcsTheme.colors.emergency else valueColor(reading, lost),
        containerColor = if (unhealthy) {
            GcsTheme.colors.emergencyContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        compact = compact,
        shortValue = withAge(short, reading),
    )
}

@Composable
private fun batteryText(state: BatteryChipState): String {
    val noValue = stringResource(id = R.string.hud_no_value)
    return when (state) {
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
}

/** The remaining energy alone, or the voltage when there is none; unhealthy is still said in words. */
@Composable
private fun batteryShortText(measured: BatteryChipState.Measured): String {
    val remaining = measured.remaining?.let { stringResource(id = R.string.hud_value_percent, it) }
        ?: measured.voltage?.let { stringResource(id = R.string.hud_value_volts, it) }
        ?: stringResource(id = R.string.hud_no_value)
    return if (measured.healthy) remaining else stringResource(id = R.string.hud_battery_unhealthy, remaining)
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
    compact: Boolean = false,
    shortValue: String = value,
) {
    if (compact) {
        val description = "$label $value"
        Surface(
            // Without its label and its whole value on screen, a compact chip still says both to TalkBack.
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            shape = ChipShape,
            color = containerColor,
        ) {
            Text(
                text = shortValue.uppercase(),
                modifier = Modifier.padding(horizontal = CompactChipSidePadding, vertical = CompactChipPadding),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = CompactChipText,
                    letterSpacing = CompactChipLetterSpacing,
                ),
                color = valueColor,
            )
        }
        return
    }
    Surface(shape = ChipShape, color = containerColor) {
        Column(
            modifier = Modifier.padding(
                horizontal = GcsTheme.spacing.chipPadding,
                vertical = GcsTheme.spacing.chipPadding / 2,
            ),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value.uppercase(), style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

/** The fix as short as it reads on the prototype's phone bar. */
@Composable
private fun fixTypeShortText(fixType: GpsFixType): String = when (fixType) {
    GpsFixType.RtkFloat -> stringResource(id = R.string.hud_fix_rtk_float_short)
    GpsFixType.RtkFixed -> stringResource(id = R.string.hud_fix_rtk_fixed_short)
    else -> fixTypeText(fixType)
}

/** The prototype's phone chips: 4 px above and below the value, 5 beside it, 12 px type, tight. */
private val CompactChipPadding = 4.dp
private val CompactChipSidePadding = 5.dp
private val CompactChipText = 12.sp
private val CompactChipLetterSpacing = 0.02.em
