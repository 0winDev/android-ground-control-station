package com.owindev.gcs.core.designsystem.confirm

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-050")
class SlideToConfirmStateTest {

    private val travel = 600f
    private val rtlOnSystem1 = "RTL 1"
    private val rtlOnSystem2 = "RTL 2"
    private var confirmations = 0
    private val countConfirmation: () -> Unit = { confirmations++ }

    private lateinit var state: SlideToConfirmState

    @BeforeEach
    fun setUp() {
        state = SlideToConfirmState(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)
        state.onTrackMeasured(travel)
    }

    @Test
    fun `GIVEN the thumb slid to the end WHEN released THEN confirms once and the thumb goes back`() {
        state.slide(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN the thumb slid short of the end WHEN released THEN does not confirm and the thumb goes back`() {
        state.slide(travel - 1f)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a slide already released at the end WHEN released again THEN does not confirm a second time`() {
        state.slide(travel)
        state.onDragEnd()

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the gesture is cancelled THEN does not confirm and the thumb goes back`() {
        state.slide(travel)

        state.onDragCancel()

        confirmations shouldBeEqualTo 0
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a gesture cancelled at the end WHEN a release follows THEN does not confirm`() {
        state.slide(travel)
        state.onDragCancel()

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the key changes THEN the thumb goes back and the release does not confirm`() {
        state.slide(travel)
        state.update(key = rtlOnSystem2, enabled = true, onConfirm = countConfirmation)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN the key changed and came back during a slide WHEN released at the end THEN does not confirm`() {
        state.onDragStart()
        state.update(key = rtlOnSystem2, enabled = true, onConfirm = countConfirmation)
        state.update(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)
        state.onDrag(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN the same key set again during a slide WHEN released at the end THEN confirms`() {
        state.onDragStart()
        state.update(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)
        state.onDrag(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a slide started after the key changed WHEN released at the end THEN confirms on the new key`() {
        state.update(key = rtlOnSystem2, enabled = true, onConfirm = countConfirmation)
        state.slide(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a new callback during a slide WHEN released at the end THEN only the new callback is called`() {
        var oldConfirmations = 0
        state = SlideToConfirmState(key = rtlOnSystem1, enabled = true, onConfirm = { oldConfirmations += 1 })
        state.onTrackMeasured(travel)
        state.slide(travel)
        state.update(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)

        state.onDragEnd()

        oldConfirmations shouldBeEqualTo 0
        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN the thumb at the end WHEN disabled THEN the thumb goes back and the release does not confirm`() {
        state.slide(travel)
        state.update(key = rtlOnSystem1, enabled = false, onConfirm = countConfirmation)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a slide disabled and enabled again WHEN released at the end THEN does not confirm`() {
        state.onDragStart()
        state.update(key = rtlOnSystem1, enabled = false, onConfirm = countConfirmation)
        state.update(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)
        state.onDrag(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the track is measured again THEN the release does not confirm`() {
        state.slide(travel)
        state.onTrackMeasured(travel / 2)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN the same track measured during a slide WHEN released at the end THEN confirms`() {
        state.slide(travel)
        state.onTrackMeasured(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a disabled control WHEN slid to the end THEN the thumb does not move`() {
        state.update(key = rtlOnSystem1, enabled = false, onConfirm = countConfirmation)

        state.slide(travel)

        state.offset shouldBeEqualTo 0f
    }

    @Test
    fun `GIVEN a disabled control slid to the end WHEN released THEN does not confirm`() {
        state.update(key = rtlOnSystem1, enabled = false, onConfirm = countConfirmation)
        state.slide(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN a track not measured yet WHEN released after a drag THEN does not confirm`() {
        state = SlideToConfirmState(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)
        state.slide(travel)

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
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
    fun `GIVEN an enabled control WHEN the accessibility action runs THEN confirms once`() {
        state.update(key = rtlOnSystem1, enabled = true, onConfirm = countConfirmation)

        val result = state.confirmByAccessibility()

        result.shouldBeTrue()
        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a slide at the end never ended WHEN a new slide is released unmoved THEN does not confirm`() {
        state.slide(travel)
        state.onDragStart()

        state.onDragEnd()

        confirmations shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN the thumb at the end WHEN the accessibility action runs and the slide is released THEN confirms once`() {
        state.slide(travel)
        state.confirmByAccessibility()

        state.onDragEnd()

        confirmations shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN a disabled control WHEN the accessibility action runs THEN does not confirm`() {
        state.update(key = rtlOnSystem1, enabled = false, onConfirm = countConfirmation)

        val result = state.confirmByAccessibility()

        result.shouldBeFalse()
        confirmations shouldBeEqualTo 0
    }

    private fun SlideToConfirmState.slide(distance: Float) {
        onDragStart()
        onDrag(distance)
    }
}
