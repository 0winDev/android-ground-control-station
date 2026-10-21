package com.owindev.gcs.feature.hud

import androidx.compose.ui.unit.dp
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-034")
class StableInsetTest {

    @Test
    fun `GIVEN nothing covered WHEN rounding the inset THEN is none`() {
        val covered = 0.dp

        val result = stableInset(covered)

        result shouldBeEqualTo 0.dp
    }

    @Test
    fun `GIVEN a banner a few dp taller WHEN rounding the inset THEN stays on the same step`() {
        val covered = listOf(65.dp, 72.dp, 80.dp)

        val result = covered.map(::stableInset)

        result shouldBeEqualTo listOf(80.dp, 80.dp, 80.dp)
    }

    @Test
    fun `GIVEN a banner a line taller WHEN rounding the inset THEN moves to the next step`() {
        val covered = 81.dp

        val result = stableInset(covered)

        result shouldBeEqualTo 96.dp
    }
}
