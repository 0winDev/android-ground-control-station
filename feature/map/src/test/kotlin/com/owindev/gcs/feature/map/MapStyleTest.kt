package com.owindev.gcs.feature.map

import androidx.compose.ui.graphics.Color
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.maplibre.compose.style.BaseStyle

@Tag("REQ-034")
class MapStyleTest {

    @Test
    fun `GIVEN the night palette WHEN choosing the style THEN is OpenFreeMap's dark over HTTPS`() {
        val palette = GcsPalette.NIGHT

        val result = remoteStyleOf(palette)

        result shouldBeEqualTo BaseStyle.Uri("https://tiles.openfreemap.org/styles/dark")
    }

    @Test
    fun `GIVEN the day palette WHEN choosing the style THEN is OpenFreeMap's liberty over HTTPS`() {
        val palette = GcsPalette.DAY

        val result = remoteStyleOf(palette)

        result shouldBeEqualTo BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    }

    @Test
    fun `GIVEN the night background WHEN building the fallback style THEN holds only that background`() {
        val background = Color(NIGHT_BACKGROUND)

        val result = fallbackStyleOf(background)

        result shouldBeEqualTo BaseStyle.Json(
            """{"version":8,"sources":{},"layers":[""" +
                """{"id":"background","type":"background","paint":{"background-color":"#0B0E11"}}]}""",
        )
    }
}

/** `Night0` in `:core:designsystem`. */
private const val NIGHT_BACKGROUND = 0xFF0B0E11
