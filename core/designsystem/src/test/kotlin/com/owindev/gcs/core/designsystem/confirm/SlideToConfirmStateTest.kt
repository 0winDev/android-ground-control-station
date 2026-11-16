package com.owindev.gcs.core.designsystem.confirm

import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-050")
class SlideToConfirmStateTest {

    private val thumb = 100f
    private val travel = 600f
    private val width = thumb + travel
    private val minimumTravel = 300f
    private val slop = 8f
    private val onThumb = 50f
    private val rtl = "RTL"
    private val land = "Land"
    private val system1 = 1
    private val system2 = 2
    private val route1 = "route 1"
    private val route2 = "route 2"
    private val confirmedKeys = mutableListOf<String>()
    private val recordConfirmation: (String) -> Unit = { confirmedKeys += it }

    private lateinit var state: SlideToConfirmState<String>

    @BeforeEach
    fun setUp() {
        state = SlideToConfirmState(rtl, system1, route1, enabled = true, onConfirm = recordConfirmation)
        state.onTrackMeasured(width, thumb, minimumTravel)
    }

    @Test
    fun `GIVEN the thumb slid to the end WHEN released THEN confirms once with the key it started on`() {
        state.slide(travel)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN the thumb slid short of the end WHEN released THEN does not confirm and the thumb goes back`() {
        state.slide(travel - 1f)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a slide already released at the end WHEN released again THEN does not confirm a second time`() {
        state.slide(travel)
        state.onDragEnd()

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the gesture is cancelled THEN does not confirm and the thumb goes back`() {
        state.slide(travel)

        state.onDragCancel()

        confirmedKeys.shouldBeEmpty()
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a gesture cancelled at the end WHEN a release follows THEN does not confirm`() {
        state.slide(travel)
        state.onDragCancel()

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the key changes THEN the thumb goes back and the release does not confirm`() {
        state.slide(travel)
        state.updateWith(confirmKey = route2)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the action changes THEN the release does not confirm`() {
        state.slide(travel)
        state.updateWith(action = land)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the system ID changes THEN the release does not confirm`() {
        state.slide(travel)
        state.updateWith(systemId = system2)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the key changed and came back during a slide WHEN released at the end THEN does not confirm`() {
        state.onDragStart(onThumb, slop)
        state.updateWith(confirmKey = route2)
        state.updateWith(confirmKey = route1)
        state.onDrag(travel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the same values set again during a slide WHEN released at the end THEN confirms`() {
        state.onDragStart(onThumb, slop)
        state.updateWith()
        state.onDrag(travel)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN a slide started after the key changed WHEN released at the end THEN confirms with the new key`() {
        state.updateWith(confirmKey = route2)
        state.slide(travel)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route2)
    }

    @Test
    fun `GIVEN a new callback during a slide WHEN released at the end THEN only the new callback is called`() {
        val oldConfirmedKeys = mutableListOf<String>()
        state = SlideToConfirmState(rtl, system1, route1, enabled = true, onConfirm = { oldConfirmedKeys += it })
        state.onTrackMeasured(width, thumb, minimumTravel)
        state.slide(travel)
        state.updateWith()

        state.onDragEnd()

        oldConfirmedKeys.shouldBeEmpty()
        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN the thumb at the end WHEN disabled THEN the thumb goes back and the release does not confirm`() {
        state.slide(travel)
        state.updateWith(enabled = false)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a slide disabled and enabled again WHEN released at the end THEN does not confirm`() {
        state.onDragStart(onThumb, slop)
        state.updateWith(enabled = false)
        state.updateWith(enabled = true)
        state.onDrag(travel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN a disabled control WHEN slid to the end THEN the thumb does not move`() {
        state.updateWith(enabled = false)

        state.slide(travel)

        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a disabled control slid to the end WHEN released THEN does not confirm`() {
        state.updateWith(enabled = false)
        state.slide(travel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN a drag noticed off the thumb WHEN slid THEN the thumb does not move`() {
        val offTheThumb = thumb + slop + 1f

        state.slide(travel, from = offTheThumb)

        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a drag noticed off the thumb and slid to the end WHEN released THEN does not confirm`() {
        state.slide(travel, from = thumb + slop + 1f)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN a drag noticed within the slop past the thumb WHEN released at the end THEN confirms`() {
        state.slide(travel, from = thumb + slop)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN a track shorter than the minimum travel WHEN released at its end THEN does not confirm`() {
        state.onTrackMeasured(thumb + minimumTravel - 1f, thumb, minimumTravel)
        state.slide(minimumTravel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN a track exactly the minimum travel long WHEN released at its end THEN confirms`() {
        state.onTrackMeasured(thumb + minimumTravel, thumb, minimumTravel)
        state.slide(minimumTravel)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN a track not measured yet WHEN released after a drag THEN does not confirm`() {
        state = SlideToConfirmState(rtl, system1, route1, enabled = true, onConfirm = recordConfirmation)
        state.slide(travel, from = 0f)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the track is measured again THEN the release does not confirm`() {
        state.slide(travel)
        state.onTrackMeasured(width - 1f, thumb, minimumTravel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN a new minimum travel is measured THEN the release does not confirm`() {
        state.slide(travel)
        state.onTrackMeasured(width, thumb, minimumTravel / 2)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN a new thumb width is measured THEN the release does not confirm`() {
        state.slide(travel)
        state.onTrackMeasured(width + 1f, thumb + 1f, minimumTravel)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the same track measured during a slide WHEN released at the end THEN confirms`() {
        state.slide(travel)
        state.onTrackMeasured(width, thumb, minimumTravel)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN a drag past the end WHEN slid THEN the thumb stops at the end`() {
        val beyondTheEnd = travel * 2

        state.slide(beyondTheEnd)

        state.offset shouldBeEqualTo travel
    }

    @Test
    fun `GIVEN a drag back past the start WHEN slid THEN the thumb stops at the start`() {
        val beforeTheStart = -travel

        state.slide(beforeTheStart)

        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a slide a second pointer carries on after the first lifts WHEN released at the end THEN confirms`() {
        state.slide(travel / 2)
        state.onDrag(travel / 2)

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN a slide at the end never ended WHEN a new slide is released unmoved THEN does not confirm`() {
        state.slide(travel)
        state.onDragStart(onThumb, slop)

        state.onDragEnd()

        confirmedKeys.shouldBeEmpty()
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the accessibility action runs and the slide is released THEN confirms once`() {
        state.slide(travel)
        state.confirmByAccessibility()

        state.onDragEnd()

        confirmedKeys shouldBeEqualTo listOf(route1)
    }

    @Test
    fun `GIVEN an enabled control WHEN the accessibility action runs THEN confirms once with the current key`() {
        state.updateWith(confirmKey = route2)

        val result = state.confirmByAccessibility()

        result.shouldBeTrue()
        confirmedKeys shouldBeEqualTo listOf(route2)
    }

    @Test
    fun `GIVEN a disabled control WHEN the accessibility action runs THEN does not confirm`() {
        state.updateWith(enabled = false)

        val result = state.confirmByAccessibility()

        result.shouldBeFalse()
        confirmedKeys.shouldBeEmpty()
    }

    private fun SlideToConfirmState<String>.slide(distance: Float, from: Float = onThumb) {
        onDragStart(from, slop)
        onDrag(distance)
    }

    private fun SlideToConfirmState<String>.updateWith(
        action: String = rtl,
        systemId: Int = system1,
        confirmKey: String = route1,
        enabled: Boolean = true,
    ) {
        update(action, systemId, confirmKey, enabled, recordConfirmation)
    }
}
