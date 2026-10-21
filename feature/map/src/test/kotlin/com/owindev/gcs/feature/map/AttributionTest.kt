package com.owindev.gcs.feature.map

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-034")
class AttributionTest {

    @Test
    fun `GIVEN OpenFreeMap's attribution WHEN shown on the map THEN is its text with no link`() {
        val attributions = listOf(OPENFREEMAP)

        val result = plainAttribution(attributions)

        result shouldBeEqualTo "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    }

    @Test
    fun `GIVEN two sources WHEN shown on the map THEN are one line`() {
        val attributions = listOf("<a href=\"https://a.example\">A</a>", "B &amp; C")

        val result = plainAttribution(attributions)

        result shouldBeEqualTo "A B & C"
    }
}

/** As `https://tiles.openfreemap.org/planet` declares it, read on 2026-10-21. */
private const val OPENFREEMAP = "<a href=\"https://openfreemap.org\" target=\"_blank\">OpenFreeMap</a> " +
    "<a href=\"https://www.openmaptiles.org/\" target=\"_blank\">&copy; OpenMapTiles</a> Data from " +
    "<a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">OpenStreetMap</a>"
