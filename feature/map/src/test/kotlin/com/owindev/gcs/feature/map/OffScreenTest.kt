package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNear
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** A 400 × 200 dp visible area beside a 300 dp panel, centred on (500, 100). */
@Tag("REQ-034")
class OffScreenTest {

    @Test
    fun `GIVEN the vehicle inside the visible area WHEN judging it THEN there is nothing to point at`() {
        val location = DpOffset(650.dp, 20.dp)

        val result = offScreenDirection(location, VISIBLE)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN the vehicle on the visible area's edge WHEN judging it THEN is still inside`() {
        val location = DpOffset(VISIBLE.right, VISIBLE.bottom)

        val result = offScreenDirection(location, VISIBLE)

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN the vehicle straight above WHEN judging it THEN points up`() {
        val location = DpOffset(500.dp, (-300).dp)

        val result = offScreenDirection(location, VISIBLE)

        result shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN the vehicle to the right WHEN judging it THEN points right`() {
        val location = DpOffset(900.dp, 100.dp)

        val result = offScreenDirection(location, VISIBLE)

        result shouldBeEqualTo 90f
    }

    @Test
    fun `GIVEN the vehicle below WHEN judging it THEN points down`() {
        val location = DpOffset(500.dp, 700.dp)

        val result = offScreenDirection(location, VISIBLE)

        result shouldBeEqualTo 180f
    }

    @Test
    fun `GIVEN the vehicle under the panel on the left WHEN judging it THEN points left`() {
        val location = DpOffset(100.dp, 100.dp)

        val result = offScreenDirection(location, VISIBLE)

        result shouldBeEqualTo 270f
    }

    @Test
    fun `GIVEN the vehicle up and to the left WHEN judging it THEN points between them`() {
        val location = DpOffset(200.dp, (-200).dp)

        val result = offScreenDirection(location, VISIBLE)

        result.shouldNotBeNull().shouldBeNear(315f, TOLERANCE)
    }
}

private val VISIBLE = DpRect(left = 300.dp, top = 0.dp, right = 700.dp, bottom = 200.dp)

private const val TOLERANCE = 1e-4f
