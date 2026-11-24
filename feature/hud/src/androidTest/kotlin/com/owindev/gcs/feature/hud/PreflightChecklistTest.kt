package com.owindev.gcs.feature.hud

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test

/**
 * Arm's pre-flight checklist on screen (REQ-048): the slide shows only when nothing holds arming. Names use underscores:
 * DEX before API 30 (minSdk 26) refuses spaces in method names.
 */
class PreflightChecklistTest {

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val acknowledged = mutableListOf<PreflightItem>()

    @Test
    fun `GIVEN_a_red_item_WHEN_the_checklist_shows_THEN_there_is_no_slide_and_the_reason_is_shown`() {
        showChecklist(listOf(ChecklistItem(GPS_RED), ChecklistItem(LINK_OK)), key = null, held = RED_ONE)

        val result = listOf(slides(), compose.onAllNodesWithText(redReason()).fetchSemanticsNodes().size)

        result shouldBeEqualTo listOf(0, 1)
    }

    @Test
    fun `GIVEN_an_unacknowledged_amber_item_WHEN_the_checklist_shows_THEN_there_is_no_slide`() {
        showChecklist(listOf(ChecklistItem(LIMITS_AMBER)), key = null, held = CommandUnavailable.CautionsToAcknowledge)

        val result = slides()

        result shouldBeEqualTo 0
    }

    @Test
    fun `GIVEN_every_item_clear_WHEN_the_checklist_shows_THEN_the_slide_is_shown`() {
        showChecklist(listOf(ChecklistItem(LIMITS_AMBER, acknowledged = true), ChecklistItem(LINK_OK)), ARM_KEY, null)

        val result = slides()

        result shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN_an_unacknowledged_amber_item_WHEN_its_button_is_tapped_THEN_that_item_is_acknowledged`() {
        showChecklist(listOf(ChecklistItem(LIMITS_AMBER)), key = null, held = CommandUnavailable.CautionsToAcknowledge)

        compose.onNodeWithText(context.getString(R.string.hud_checklist_acknowledge).uppercase()).performClick()
        compose.waitForIdle()
        val result = acknowledged.toList()

        result shouldBeEqualTo listOf(LIMITS_AMBER)
    }

    private fun showChecklist(checklist: List<ChecklistItem>, key: ConfirmKey?, held: CommandUnavailable?) {
        compose.setContent {
            GcsTheme {
                ChecklistSheetContent(
                    sheet = CommandSheet(
                        command = VehicleCommand.ARM,
                        systemId = SYSTEM_ID,
                        altitudeMetres = null,
                        key = key,
                        unavailable = held,
                        checklist = checklist,
                    ),
                    checklist = checklist,
                    actions = CommandActions(onAcknowledgeCaution = { acknowledged += it }),
                )
            }
        }
    }

    private fun slides(): Int {
        val action = context.getString(R.string.hud_command_action_arm)
        val label = context.getString(DesignSystemR.string.slide_to_confirm, action, SYSTEM_ID)
        return compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size
    }

    private fun redReason(): String = context.resources.getQuantityString(R.plurals.hud_command_preflight_red, 1, 1)

    private companion object {
        const val SYSTEM_ID = 1
        val ARM_KEY = ConfirmKey(VehicleCommand.ARM, CommandKey(SystemId(SYSTEM_ID), connection = 4), null)
        val RED_ONE = CommandUnavailable.PreflightRed(items = 1)
        val LINK_OK = PreflightItem(PreflightKind.LINK, PreflightColour.GREEN, PreflightReason.Ok)
        val GPS_RED = PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NoFix3d)
        val LIMITS_AMBER =
            PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead)
    }
}
