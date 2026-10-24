package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * when the screen is too narrow, so none is pushed off it or hidden. Once the link is lost they keep the last known
 * values, all greyed as the last data under the banner (REQ-017). [compact], on a phone, puts the switch and the
 * buttons in a menu and the chips, short and without their labels, beside it, so the bar takes one line when they fit.
 */
@Composable
internal fun StatusBar(
    state: HudUiState,
    onToggleTheme: () -> Unit,
    onOpenLicences: () -> Unit = {},
    onOpenRecordings: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (compact) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CompactChipGap),
                verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenMenu(
                    state = state,
                    onToggleTheme = onToggleTheme,
                    onOpenLicences = onOpenLicences,
                    onOpenRecordings = onOpenRecordings,
                )
                StatusChipItems(state = state, compact = true)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
            ) {
                ThemeToggle(onToggle = onToggleTheme)
                ScreenButtons(state = state, onOpenLicences = onOpenLicences, onOpenRecordings = onOpenRecordings)
                StatusChips(state = state, alignment = Alignment.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Its icon shows the palette in use; its text and its description say what a tap does, so it never reads as a
 * state. One tap switches at once: changing palette is harmless and needs no confirmation.
 */
@Composable
private fun ThemeToggle(onToggle: () -> Unit) {
    val toDay = GcsTheme.palette == GcsPalette.NIGHT
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
            modifier = Modifier.padding(
                horizontal = GcsTheme.spacing.chipPadding,
                vertical = GcsTheme.spacing.chipPadding / 2,
            ),
            horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
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

/** The prototype's phone bar: 4 px between chips, so six fit on one line at 360 dp. */
private val CompactChipGap = 4.dp
