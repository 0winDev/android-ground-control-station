package com.owindev.gcs.feature.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-034")
class FollowDeadZoneTest {

    @Test
    fun `GIVEN the vehicle 3·9 dp from the centre WHEN following it THEN the camera stays`() {
        val vehicle = DpOffset(CENTRE.x + 3.9.dp, CENTRE.y)

        val result = withinDeadZone(CENTRE, vehicle)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN the vehicle 3 dp across and 3 dp down WHEN following it THEN the camera moves`() {
        val vehicle = DpOffset(CENTRE.x + 3.dp, CENTRE.y + 3.dp)

        val result = withinDeadZone(CENTRE, vehicle)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN the vehicle exactly at the dead zone's edge WHEN following it THEN the camera moves`() {
        val vehicle = DpOffset(CENTRE.x, CENTRE.y - FOLLOW_DEAD_ZONE)

        val result = withinDeadZone(CENTRE, vehicle)

        result shouldBeEqualTo false
    }
}

private val CENTRE = DpOffset(808.dp, 352.dp)
