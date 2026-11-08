package com.owindev.gcs.feature.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.background.BackgroundLinkWarning

/**
 * Why the background link may fail the operator (REQ-046), most urgent first, each with the way to fix it: the
 * app's page in the system's settings, where notifications and background activity are allowed.
 */
@Composable
internal fun BackgroundLinkBanners(warnings: Set<BackgroundLinkWarning>, onOpenSettings: () -> Unit) {
    warnings.sortedByDescending { it.level }.forEach { warning ->
        val style = styleOf(warning.level)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = style.liveRegion },
            shape = ChipShape,
            color = style.container,
            contentColor = style.content,
        ) {
            Row(
                modifier = Modifier.padding(all = GcsTheme.spacing.chipPadding),
                horizontalArrangement = Arrangement.spacedBy(GcsTheme.spacing.chip),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative: the text next to it says what is wrong.
                Icon(painter = painterResource(id = style.icon), contentDescription = null)
                Text(
                    text = stringResource(id = warning.message()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = GcsTheme.sizes.button),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = style.content),
                    border = BorderStroke(width = 1.dp, color = style.content),
                ) {
                    Text(text = stringResource(id = R.string.hud_background_settings).uppercase())
                }
            }
        }
    }
}

private fun BackgroundLinkWarning.message(): Int = when (this) {
    BackgroundLinkWarning.NOTIFICATION_HIDDEN -> R.string.hud_background_notification_hidden
    BackgroundLinkWarning.BATTERY_OPTIMIZED -> R.string.hud_background_battery_optimized
    BackgroundLinkWarning.BACKGROUND_RESTRICTED -> R.string.hud_background_restricted
}
