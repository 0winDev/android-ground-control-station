package com.owindev.gcs.feature.hud

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test

/**
 * Arm's pre-flight checklist on a 360 dp phone in Spanish (REQ-048), whose caution word is the longest: every text,
 * the rows' titles and acknowledge buttons beside it included, shows whole. Names use underscores: DEX before API 30
 * refuses spaces in method names.
 */
class ChecklistPhoneTest {

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun `GIVEN_unacknowledged_amber_items_in_Spanish_on_a_phone_WHEN_the_checklist_shows_THEN_every_text_fits`() {
        show(listOf(ChecklistItem(PREARM_AMBER), ChecklistItem(LIMITS_AMBER), ChecklistItem(LINK_OK)))

        val result = textsCut()

        result shouldBeEqualTo emptyList()
    }

    private fun show(checklist: List<ChecklistItem>) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(Upright) then
                    DeviceConfigurationOverride.Locales(LocaleList(SPANISH)),
            ) {
                GcsTheme {
                    ChecklistSheetContent(
                        sheet = CommandSheet(
                            command = VehicleCommand.ARM,
                            systemId = SYSTEM_ID,
                            altitudeMetres = null,
                            key = null,
                            unavailable = CommandUnavailable.CautionsToAcknowledge,
                            checklist = checklist,
                        ),
                        checklist = checklist,
                        actions = CommandActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The texts squeezed to nothing, ellipsised, broken inside a word or running past the screen's edge.
     * `hasVisualOverflow` cannot tell: it compares a single line with the width offered, not the one it needs.
     */
    private fun textsCut(): List<String> {
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        return compose.onAllNodes(HasText, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.layout()?.let { node to it } }
            .filter { (node, layout) ->
                layout.size.width == 0 || layout.cut() || node.boundsInRoot.right > width
            }
            .map { (_, layout) -> layout.layoutInput.text.text }
    }

    private fun TextLayoutResult.cut(): Boolean {
        val text = layoutInput.text.text
        return (0 until lineCount).any { line ->
            isLineEllipsized(line) || text.brokenAt(getLineEnd(line))
        }
    }

    private fun String.brokenAt(end: Int): Boolean =
        end < length && !this[end - 1].isWhitespace() && !this[end].isWhitespace()

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.firstOrNull()
    }

    private companion object {
        const val SYSTEM_ID = 1
        const val SPANISH = "es"

        /** A 360 × 640 dp phone less its system bars. */
        val Upright = DpSize(width = 360.dp, height = 564.dp)
        val HasText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val LINK_OK = PreflightItem(PreflightKind.LINK, PreflightColour.GREEN, PreflightReason.Ok)
        val LIMITS_AMBER =
            PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead)
        val PREARM_AMBER =
            PreflightItem(PreflightKind.VEHICLE_PREARM, PreflightColour.AMBER, PreflightReason.ArmingChecksDisabled)
    }
}
