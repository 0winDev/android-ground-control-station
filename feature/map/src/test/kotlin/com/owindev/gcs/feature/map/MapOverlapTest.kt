package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-034")
class MapOverlapTest {

    @Test
    fun `GIVEN home just right of the vehicle WHEN placing its label THEN the label would cover it`() {
        val home = DpOffset(60.dp, 4.dp)

        val result = labelCoversHome(home, LABEL_START, LABEL, HOME_RADIUS)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN home past the label's end WHEN placing its label THEN the label clears it`() {
        val home = DpOffset(LABEL_START + LABEL.width + HOME_RADIUS + 1.dp, 0.dp)

        val result = labelCoversHome(home, LABEL_START, LABEL, HOME_RADIUS)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN home above the label's line WHEN placing its label THEN the label clears it`() {
        val home = DpOffset(60.dp, -(LABEL.height / 2 + HOME_RADIUS + 1.dp))

        val result = labelCoversHome(home, LABEL_START, LABEL, HOME_RADIUS)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN home left of the vehicle WHEN placing its label THEN the label clears it`() {
        val home = DpOffset(-60.dp, 0.dp)

        val result = labelCoversHome(home, LABEL_START, LABEL, HOME_RADIUS)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN no home WHEN placing its label THEN the label clears it`() {
        val home: DpOffset? = null

        val result = labelCoversHome(home, LABEL_START, LABEL, HOME_RADIUS)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a phone's map and a long attribution WHEN placing the controls THEN they go above it`() {
        val width = 312.dp

        val result = controlsAboveAttribution(width, controls = 184.dp, attribution = 288.dp, gap = GAP)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN a tablet's map WHEN placing the controls THEN they stay beside the attribution`() {
        val width = 1248.dp

        val result = controlsAboveAttribution(width, controls = 184.dp, attribution = 288.dp, gap = GAP)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a phone's map and the attribution folded WHEN placing the controls THEN they stay beside it`() {
        val width = 312.dp

        val result = controlsAboveAttribution(width, controls = 184.dp, attribution = 56.dp, gap = GAP)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a short map under a tall banner WHEN placing the camera THEN it leaves the marker room`() {
        val covered = 176.dp

        val result = cameraTopInset(covered, height = 327.dp, bottom = 104.dp, room = 60.dp)

        result shouldBeEqualTo 163.dp
    }

    @Test
    fun `GIVEN a tall map WHEN placing the camera THEN it keeps the whole covered top`() {
        val covered = 176.dp

        val result = cameraTopInset(covered, height = 611.dp, bottom = 104.dp, room = 60.dp)

        result shouldBeEqualTo covered
    }

    @Test
    fun `GIVEN a map shorter than its controls WHEN placing the camera THEN the inset is never negative`() {
        val covered = 176.dp

        val result = cameraTopInset(covered, height = 100.dp, bottom = 104.dp, room = 60.dp)

        result shouldBeEqualTo 0.dp
    }

    @Test
    fun `GIVEN no attribution yet WHEN placing the controls THEN they stay at the bottom`() {
        val width = 312.dp

        val result = controlsAboveAttribution(width, controls = 184.dp, attribution = 0.dp, gap = GAP)

        result shouldBeEqualTo false
    }
}

private val LABEL_START = 34.dp
private val LABEL = DpSize(84.dp, 24.dp)
private val HOME_RADIUS = 17.dp
private val GAP = 8.dp
