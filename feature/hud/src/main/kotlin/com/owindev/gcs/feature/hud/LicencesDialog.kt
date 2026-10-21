package com.owindev.gcs.feature.hud

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme

/**
 * The map data, tiles, style and fonts the app shows, with their licences and links. The map's own attribution is
 * plain text, so a stray tap in flight never opens a browser; the links live here instead, where the OpenStreetMap
 * attribution guidelines allow them (ADR 0005). Opened only while no vehicle is armed (`licencesAvailable`).
 */
@Composable
internal fun LicencesDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(all = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(ContentSpacing),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = R.string.hud_licences_title).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = GcsTheme.sizes.button)) {
                        Text(text = stringResource(id = R.string.hud_licences_close).uppercase())
                    }
                }
                LICENCES.forEach { licence ->
                    Column {
                        Text(text = stringResource(id = licence.name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(id = licence.terms),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { uriHandler.openUri(licence.url) },
                            modifier = Modifier.heightIn(min = GcsTheme.sizes.minTouchTarget),
                        ) {
                            Text(text = licence.url)
                        }
                    }
                }
            }
        }
    }
}

/** Opens the licences; shown only while no vehicle is armed (`licencesAvailable`). */
@Composable
internal fun LicencesButton(onClick: () -> Unit) {
    val description = stringResource(id = R.string.hud_licences_open)
    Surface(
        onClick = onClick,
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
            // Decorative: the text next to it says what it opens.
            Icon(painter = painterResource(id = DesignSystemR.drawable.ic_info), contentDescription = null)
            Text(
                text = stringResource(id = R.string.hud_licences).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private class Licence(@StringRes val name: Int, @StringRes val terms: Int, val url: String)

private val LICENCES = listOf(
    Licence(R.string.hud_licence_osm, R.string.hud_licence_osm_terms, "https://www.openstreetmap.org/copyright"),
    Licence(R.string.hud_licence_openmaptiles, R.string.hud_licence_openmaptiles_terms, "https://openmaptiles.org/"),
    Licence(R.string.hud_licence_openfreemap, R.string.hud_licence_openfreemap_terms, "https://openfreemap.org/"),
    Licence(
        R.string.hud_licence_osm_liberty,
        R.string.hud_licence_osm_liberty_terms,
        "https://github.com/maputnik/osm-liberty",
    ),
    Licence(R.string.hud_licence_fonts, R.string.hud_licence_fonts_terms, "https://openfontlicense.org/"),
    Licence(
        R.string.hud_licence_material_symbols,
        R.string.hud_licence_material_symbols_terms,
        "https://www.apache.org/licenses/LICENSE-2.0",
    ),
)
