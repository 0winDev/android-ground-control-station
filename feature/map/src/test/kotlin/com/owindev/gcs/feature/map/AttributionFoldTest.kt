package com.owindev.gcs.feature.map

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-034")
class AttributionFoldTest {

    private lateinit var fold: AttributionFold

    @BeforeEach
    fun setUp() {
        fold = AttributionFold()
    }

    @Test
    fun `GIVEN the attribution shown WHEN it has shown long enough THEN it folds`() {
        fold.onShown()

        fold.onShownLongEnough()

        fold.folded shouldBeEqualTo true
    }

    @Test
    fun `GIVEN the attribution shown WHEN the map is panned THEN it folds`() {
        fold.onShown()

        fold.onGesture()

        fold.folded shouldBeEqualTo true
    }

    @Test
    fun `GIVEN the attribution not shown yet WHEN the map is panned THEN it stays unfolded`() {
        fold.onGesture()

        fold.folded shouldBeEqualTo false
    }

    @Test
    fun `GIVEN the attribution folded WHEN its button is tapped THEN it opens`() {
        fold.onShown()
        fold.onGesture()

        fold.onOpen()

        fold.folded shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a new attribution not shown yet WHEN the map is panned THEN it stays unfolded`() {
        fold.onShown()
        fold.onAttributionChanged()

        fold.onGesture()

        fold.folded shouldBeEqualTo false
    }
}
