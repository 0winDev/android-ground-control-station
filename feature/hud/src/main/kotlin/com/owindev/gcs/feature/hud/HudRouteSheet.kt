package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.owindev.gcs.core.designsystem.confirm.SlideToConfirm
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.RouteAddress
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand

/** Why every command is held by the vehicle's route (REQ-051); only a route heard alone can be reviewed. */
@Composable
internal fun RouteLine(notice: RouteNotice, onReview: () -> Unit) {
    val text = when (notice) {
        is RouteNotice.Changed ->
            stringResource(id = R.string.hud_route_changed, notice.from.display, notice.to.display)

        is RouteNotice.Alternating -> stringResource(
            id = R.string.hud_route_alternating,
            notice.addresses.joinToString(separator = ", ") { it.display },
            notice.pinned.display,
        )
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = ChipShape, color = GcsTheme.colors.cautionContainer) {
        Row(
            modifier = Modifier.padding(start = GcsTheme.spacing.chipPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = GcsTheme.spacing.chip),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (notice is RouteNotice.Changed) {
                TextButton(onClick = onReview, modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget)) {
                    Text(
                        text = stringResource(id = R.string.hud_route_review).uppercase(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * The review of a new route (REQ-051): both addresses, what is held, and a slide naming the vehicle. Back or a tap
 * outside cancels it; only the slide accepts the route.
 */
@Composable
internal fun RouteSheetDialog(sheet: RouteSheet, actions: CommandActions) {
    BottomSheetDialog(onDismiss = actions.onCancelRoute) { RouteSheetContent(sheet = sheet, actions = actions) }
}

@Composable
private fun RouteSheetContent(sheet: RouteSheet, actions: CommandActions) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(all = GcsTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(GcsTheme.spacing.content),
    ) {
        Text(text = stringResource(id = R.string.hud_route_title), style = MaterialTheme.typography.headlineMedium)
        Address(label = stringResource(id = R.string.hud_route_from), address = sheet.offer.from.display)
        Address(label = stringResource(id = R.string.hud_route_to), address = sheet.offer.to.display)
        Text(text = stringResource(id = R.string.hud_route_held), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(id = R.string.hud_route_not_proof),
            color = GcsTheme.colors.caution,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (sheet.slideAgain) {
            Text(
                text = stringResource(id = R.string.hud_route_slide_again),
                color = GcsTheme.colors.caution,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        SlideToConfirm(
            action = stringResource(id = R.string.hud_route_action),
            systemId = sheet.systemId,
            confirmKey = sheet.offer,
            onConfirm = actions.onConfirmRoute,
        )
        OutlinedButton(
            onClick = actions.onCancelRoute,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GcsTheme.sizes.button),
        ) {
            Text(text = stringResource(id = R.string.hud_command_cancel).uppercase())
        }
    }
}

@Composable
private fun Address(label: String, address: String) {
    Column {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = address, style = GcsTheme.telemetryTypography.value)
    }
}

@Preview(widthDp = 360)
@Composable
private fun RouteNightPreview() {
    RoutePreview(palette = GcsPalette.NIGHT)
}

@Preview(widthDp = 360)
@Composable
private fun RouteDayPreview() {
    RoutePreview(palette = GcsPalette.DAY)
}

@Composable
private fun RoutePreview(palette: GcsPalette) {
    val from = RouteAddress("192.168.1.20", port = 14550)
    val to = RouteAddress("10.0.0.7", port = 14551)
    GcsTheme(palette = palette) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                RouteSheetContent(
                    sheet = RouteSheet(
                        systemId = 1,
                        offer = RouteOffer(CommandKey(SystemId(1), connection = 1), from, to),
                    ),
                    actions = CommandActions(),
                )
                CommandArea(
                    bar = previewBar(CommandUnavailable.RouteChanged),
                    feedback = null,
                    actions = CommandActions(),
                    route = RouteNotice.Changed(from = from, to = to),
                )
                CommandArea(
                    bar = previewBar(CommandUnavailable.RouteAlternating),
                    feedback = null,
                    actions = CommandActions(),
                    route = RouteNotice.Alternating(pinned = from, addresses = listOf(to, from)),
                )
            }
        }
    }
}

private fun previewBar(held: CommandUnavailable) = CommandBar(
    ground = OfferedCommand(VehicleCommand.TAKE_OFF, held),
    returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH, held),
    land = OfferedCommand(VehicleCommand.LAND, held),
)
