package com.owindev.gcs.feature.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme

/**
 * On a phone the status bar has no room for the theme switch and the screen buttons beside the chips: they go in a
 * menu, each item under the same rule as its button, so arming takes the screens away from it too.
 */
@Composable
internal fun ScreenMenu(
    state: HudUiState,
    onToggleTheme: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenRecordings: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(id = DesignSystemR.drawable.ic_more_vert),
                contentDescription = stringResource(id = R.string.hud_menu_open),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val toDay = GcsTheme.palette == GcsPalette.NIGHT
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            id = if (toDay) R.string.hud_theme_switch_to_day else R.string.hud_theme_switch_to_night,
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    onToggleTheme()
                },
                leadingIcon = {
                    // Decorative: the text says the action; the icon only shows the palette in use.
                    val icon = if (toDay) DesignSystemR.drawable.ic_dark_mode else DesignSystemR.drawable.ic_light_mode
                    Icon(painter = painterResource(id = icon), contentDescription = null)
                },
            )
            if (state.licencesAvailable) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.hud_licences_open)) },
                    onClick = {
                        expanded = false
                        onOpenLicences()
                    },
                )
            }
            onOpenRecordings?.let { open ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.hud_recordings_open)) },
                    onClick = {
                        expanded = false
                        open()
                    },
                )
            }
        }
    }
}
