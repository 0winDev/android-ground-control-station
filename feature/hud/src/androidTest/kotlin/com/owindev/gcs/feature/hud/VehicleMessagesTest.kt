package com.owindev.gcs.feature.hud

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test

/**
 * The vehicle's messages on the flight screen (REQ-047), below the link-lost emergency (REQ-017). Names use
 * underscores: DEX before API 30 (minSdk 26) refuses spaces in method names.
 */
class VehicleMessagesTest {

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `GIVEN_a_long_message_WHEN_shown_at_a_glance_and_in_the_list_THEN_only_the_glance_is_cut`() {
        show(connected(line(LONG_TEXT)))
        compose.onNodeWithText(LONG_TEXT, substring = true, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        val result = compose.onAllNodesWithText(LONG_TEXT, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .indices
            .map { compose.onAllNodesWithText(LONG_TEXT, substring = true, useUnmergedTree = true)[it].overflows() }

        result shouldBeEqualTo listOf(true, false)
    }

    @Test
    fun `GIVEN_a_severity_outside_MAV_SEVERITY_WHEN_shown_THEN_it_reads_as_an_advisory_with_its_value`() {
        show(connected(line(GPS_TEXT, unknownSeverity = UNKNOWN_SEVERITY)))

        val result = compose.onAllNodesWithText(
            "${context.getString(R.string.hud_message_unknown_level, advisory(), UNKNOWN_SEVERITY)} · $GPS_TEXT",
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

        result shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN_a_replayed_message_WHEN_shown_THEN_it_is_tagged_recorded`() {
        show(connected(line(GPS_TEXT, recorded = true)))

        val result = compose.onAllNodesWithText(
            context.getString(R.string.hud_recorded).uppercase(),
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes()
            .size

        result shouldBeEqualTo 1
    }

    @Test
    fun `GIVEN_the_link_lost_WHEN_the_message_list_is_opened_THEN_it_shows_below_the_emergency`() {
        show(linkLost(line(GPS_TEXT)))
        compose.onNodeWithText(context.getString(R.string.hud_messages_count, 1).uppercase()).performClick()
        compose.waitForIdle()

        val emergency = compose.onNodeWithText(context.getString(R.string.hud_link_lost_title).uppercase())
            .fetchSemanticsNode()
            .boundsInRoot
        val message = compose.onNodeWithText(GPS_TEXT, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val result = message.top >= emergency.bottom

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN_the_link_lost_WHEN_nothing_is_opened_THEN_no_message_is_at_a_glance`() {
        show(linkLost(line(GPS_TEXT)))

        val result = compose.onAllNodesWithText(GPS_TEXT, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

        result shouldBeEqualTo 0
    }

    private fun show(state: HudUiState) {
        compose.setContent { GcsTheme { HudContent(state = state, onAcknowledgeAlert = {}) } }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.overflows(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().hasVisualOverflow
    }

    private fun advisory() = context.getString(R.string.hud_alert_level_advisory)

    private fun connected(line: MessageLine) = HudUiState.Connected(
        vehicle = VEHICLE,
        port = PORT,
        datagramCount = DATAGRAMS,
        otherVehicles = emptyList(),
        messages = VehicleMessages(latest = line, kept = listOf(line)),
    )

    private fun linkLost(line: MessageLine) = HudUiState.LinkLost(
        lastKnown = VEHICLE,
        sinceLastHeartbeat = LOST_FOR,
        otherVehicles = emptyList(),
        messages = VehicleMessages(latest = null, kept = listOf(line)),
    )

    private fun line(text: String, unknownSeverity: Int? = null, recorded: Boolean = false) = MessageLine(
        text = text,
        level = AlertLevel.ADVISORY,
        unknownSeverity = unknownSeverity,
        age = 2.seconds,
        incomplete = false,
        recorded = recorded,
    )

    private companion object {
        const val PORT = 14550
        const val DATAGRAMS = 42L
        const val UNKNOWN_SEVERITY = 9
        const val GPS_TEXT = "GPS 1: detected u-blox"
        val LONG_TEXT = "EKF3 IMU0 is using GPS, origin set, yaw alignment complete, ".repeat(5).trim()
        val VEHICLE = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Guided"), armed = false)
        val LOST_FOR = 5.seconds
    }
}
