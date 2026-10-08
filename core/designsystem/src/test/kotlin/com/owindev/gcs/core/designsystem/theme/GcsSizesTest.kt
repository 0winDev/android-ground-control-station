package com.owindev.gcs.core.designsystem.theme

import androidx.compose.ui.unit.dp
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-007")
class GcsSizesTest {

    private lateinit var sizes: GcsSizes

    @BeforeEach
    fun setUp() {
        sizes = DefaultGcsSizes
    }

    @Test
    fun `GIVEN the Material minimum touch target WHEN reading the minimum touch target THEN it is not smaller`() {
        val materialMinimum = 48.dp

        val result = sizes.minTouchTarget

        result shouldBeGreaterOrEqualTo materialMinimum
    }

    @Test
    fun `GIVEN every control size WHEN taking the smallest THEN it is not below the minimum touch target`() {
        val controls = listOf(sizes.button, sizes.tab, sizes.gloveButton)

        val result = controls.min()

        result shouldBeGreaterOrEqualTo sizes.minTouchTarget
    }
}
