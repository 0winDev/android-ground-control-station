package com.owindev.gcs.core.designsystem.confirm

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.owindev.gcs.core.designsystem.R
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test

/**
 * What TalkBack can do with [SlideToConfirm] (REQ-050): confirm only through its custom action, never a tap. Names use
 * underscores: DEX before API 30 (minSdk 26) refuses spaces in method names.
 */
class SlideToConfirmSemanticsTest {

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private val confirmed = mutableListOf<String>()

    @Test
    fun `GIVEN_an_enabled_slide_WHEN_reading_its_actions_THEN_it_has_no_click_action`() {
        val slide = showSlide()

        val result = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick).matches(slide.fetchSemanticsNode())

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN_an_enabled_slide_WHEN_it_is_clicked_THEN_nothing_is_confirmed`() {
        val slide = showSlide()

        slide.performClick()
        compose.waitForIdle()
        val result = confirmed.toList()

        result shouldBeEqualTo emptyList<String>()
    }

    @Test
    fun `GIVEN_an_enabled_slide_WHEN_it_is_double_tapped_THEN_nothing_is_confirmed`() {
        val slide = showSlide()

        slide.performTouchInput { doubleClick() }
        compose.waitForIdle()
        val result = confirmed.toList()

        result shouldBeEqualTo emptyList<String>()
    }

    @Test
    fun `GIVEN_an_enabled_slide_WHEN_its_custom_action_runs_THEN_it_confirms_its_key_once`() {
        val slide = showSlide()

        val actions = slide.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.single().action() }
        compose.waitForIdle()
        val result = confirmed.toList()

        result shouldBeEqualTo listOf(KEY)
    }

    @Test
    fun `GIVEN_a_disabled_slide_WHEN_reading_it_THEN_it_is_disabled_and_announces_its_reason`() {
        val slide = showSlide(disabledReason = REASON)

        val node = slide.fetchSemanticsNode().config
        val result =
            listOf(
                SemanticsProperties.Disabled in node,
                node.getOrElseNullable(SemanticsProperties.StateDescription) {
                    null
                },
            )

        result shouldBeEqualTo listOf(true, REASON)
    }

    @Test
    fun `GIVEN_a_disabled_slide_WHEN_reading_its_actions_THEN_it_has_no_action_that_confirms`() {
        val slide = showSlide(disabledReason = REASON)

        val node = slide.fetchSemanticsNode().config
        val result = listOf(SemanticsActions.CustomActions in node, SemanticsActions.OnClick in node)

        result shouldBeEqualTo listOf(false, false)
    }

    private fun showSlide(disabledReason: String? = null): SemanticsNodeInteraction {
        compose.setContent {
            GcsTheme {
                SlideToConfirm(
                    action = ACTION,
                    systemId = SYSTEM_ID,
                    confirmKey = KEY,
                    onConfirm = { confirmed += it },
                    disabledReason = disabledReason,
                )
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.slide_to_confirm, ACTION, SYSTEM_ID)
        return compose.onNodeWithContentDescription(label)
    }

    private companion object {
        const val ACTION = "arm"
        const val SYSTEM_ID = 1
        const val KEY = "arm on connection 4"
        const val REASON = "1 pre-flight item red"
    }
}
