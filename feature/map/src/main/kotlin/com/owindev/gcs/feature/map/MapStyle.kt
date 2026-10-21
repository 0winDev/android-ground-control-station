package com.owindev.gcs.feature.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import org.maplibre.compose.style.BaseStyle

/** OpenFreeMap's style for [palette], over HTTPS (ADR 0005, N-3). */
internal fun remoteStyleOf(palette: GcsPalette): BaseStyle = BaseStyle.Uri(
    when (palette) {
        GcsPalette.NIGHT -> "$OPENFREEMAP_STYLES/dark"
        GcsPalette.DAY -> "$OPENFREEMAP_STYLES/liberty"
    },
)

/**
 * A style with nothing but a [background], for when the remote one cannot be loaded: it needs no network, so the
 * vehicle, trail and home, which are the map's own layers, are still drawn on it (REQ-034).
 */
internal fun fallbackStyleOf(background: Color): BaseStyle = BaseStyle.Json(
    """{"version":8,"sources":{},"layers":[""" +
        """{"id":"background","type":"background","paint":{"background-color":"${background.toHex()}"}}]}""",
)

/** `#RRGGBB`, as the style specification reads colours. */
internal fun Color.toHex(): String = "#%06X".format(toArgb() and RGB_MASK)

private const val OPENFREEMAP_STYLES = "https://tiles.openfreemap.org/styles"
private const val RGB_MASK = 0xFFFFFF
