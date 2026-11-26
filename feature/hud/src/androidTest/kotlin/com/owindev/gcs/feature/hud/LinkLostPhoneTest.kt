package com.owindev.gcs.feature.hud

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.owindev.gcs.core.designsystem.R as DesignSystemR
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.core.domain.alert.AlertLevel
import com.owindev.gcs.core.domain.vehicle.FlightMode
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test

/**
 * The link-lost emergency and the vehicle's last known data on a phone (REQ-017): the emergency first and largest,
 * the last data on screen below or beside it, whatever banners, messages, orientation or font scale. The heights
 * are a 360 × 640 dp phone's less its system bars. Names use underscores: DEX before API 30 refuses spaces.
 */
class LinkLostPhoneTest {

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun `GIVEN_a_phone_upright_WHEN_the_link_is_lost_THEN_the_last_data_are_below_the_emergency`() {
        show(size = Upright, language = SPANISH)

        val result = lastDataBelowEmergency(SPANISH)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN_a_phone_upright_WHEN_the_link_is_lost_THEN_the_banners_leave_the_vehicle_in_sight`() {
        show(size = Upright, language = SPANISH)

        val vehicle = compose.onNodeWithTag(VEHICLE_TAG).fetchSemanticsNode().boundsInRoot
        val result = listOf(
            vehicle.overlaps(emergencyBanner(SPANISH)),
            vehicle.overlaps(messagesButton(SPANISH).performScrollTo().fetchSemanticsNode().boundsInRoot),
        )

        result shouldBeEqualTo listOf(false, false)
    }

    @Test
    fun `GIVEN_a_phone_upright_in_English_WHEN_the_link_is_lost_THEN_the_last_data_are_below_the_emergency`() {
        show(size = Upright, language = ENGLISH)

        val result = lastDataBelowEmergency(ENGLISH)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN_the_message_list_open_WHEN_scrolled_to_its_end_THEN_the_emergency_stays_whole_and_uncovered`() {
        show(size = Upright, language = SPANISH)
        messagesButton(SPANISH).performScrollTo().performClick()
        compose.onNodeWithText(LOST.messages.kept.last().text, substring = true, useUnmergedTree = true)
            .performScrollTo()
        compose.waitForIdle()

        val result = listOf(onScreen(emergency(SPANISH)), othersOverlapping(emergencyBanner(SPANISH)).isEmpty())

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN_a_replay_WHEN_the_link_is_lost_THEN_its_banner_the_emergency_and_the_last_data_come_in_order`() {
        show(size = Upright, language = SPANISH, replay = REPLAY)

        val result = listOf(
            emergency(SPANISH).top >= bounds(text(SPANISH, R.string.hud_replay_title).uppercase(), SPANISH).bottom,
            lastDataBelowEmergency(SPANISH),
        )

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN_a_phone_on_its_side_WHEN_the_link_is_lost_THEN_the_last_data_show_and_nothing_covers_LOST`() {
        show(size = OnItsSide, language = SPANISH)

        val emergency = emergencyBanner(SPANISH)
        val lastData = lastData(SPANISH)
        val result = listOf(
            onScreen(lastData),
            lastData.top >= emergency.bottom || lastData.left >= emergency.right,
            othersOverlapping(emergency).isEmpty(),
        )

        result shouldBeEqualTo listOf(true, true, true)
    }

    @Test
    fun `GIVEN_a_font_scale_of_1_3_WHEN_the_link_is_lost_THEN_LOST_is_the_largest_and_the_last_data_show`() {
        show(size = Upright, language = SPANISH, fontScale = LARGE_FONT)

        val title = text(SPANISH, R.string.hud_link_lost_title).uppercase()
        val result = listOf(
            largestText() == title,
            onScreen(lastData(SPANISH)),
        )

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN_a_replay_at_a_font_scale_of_1_3_WHEN_the_link_is_lost_THEN_the_emergency_and_last_data_are_whole`() {
        show(size = Upright, language = SPANISH, fontScale = LARGE_FONT, replay = REPLAY)

        val result = listOf(onScreen(emergency(SPANISH)), onScreen(since(SPANISH)), onScreen(lastData(SPANISH)))

        result shouldBeEqualTo listOf(true, true, true)
    }

    @Test
    fun `GIVEN_another_vehicle_on_the_link_WHEN_the_link_is_lost_THEN_the_banners_leave_the_vehicle_in_sight`() {
        show(size = Upright, language = SPANISH, state = LOST.copy(otherVehicles = listOf(OTHER_SYSTEM)))

        val vehicle = compose.onNodeWithTag(VEHICLE_TAG).fetchSemanticsNode().boundsInRoot
        val result = listOf(
            vehicle.overlaps(emergencyBanner(SPANISH)),
            vehicle.overlaps(bounds(otherVehicles(SPANISH), SPANISH)),
            onScreen(lastData(SPANISH)),
        )

        result shouldBeEqualTo listOf(false, false, true)
    }

    @Test
    fun `GIVEN_the_panel_scrolled_down_WHEN_the_link_is_lost_THEN_the_last_data_header_is_in_sight`() {
        val state = mutableStateOf<HudUiState>(CONNECTED)
        show(size = Upright, language = SPANISH, state = state)
        compose.onNodeWithText(PORT.toString(), substring = true).performScrollTo()
        state.value = LOST

        compose.waitForIdle()

        onScreen(lastData(SPANISH)) shouldBeEqualTo true
    }

    private fun show(
        size: DpSize,
        language: Locale,
        fontScale: Float = 1f,
        replay: ReplayBanner? = null,
        state: HudUiState = LOST,
    ) = show(size, language, fontScale, replay, mutableStateOf(state))

    private fun show(
        size: DpSize,
        language: Locale,
        fontScale: Float = 1f,
        replay: ReplayBanner? = null,
        state: State<HudUiState>,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(fontScale) then
                    DeviceConfigurationOverride.Locales(LocaleList(language.toLanguageTag())),
            ) {
                GcsTheme {
                    HudContent(
                        state = state.value,
                        onAcknowledgeAlert = {},
                        replay = replay,
                        map = { VehicleOnMap(it) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Where the map's camera puts the vehicle (`MapScreen`): the middle of the part the HUD leaves it above the map's
     * controls, but never with less than the marker's room above them.
     */
    @Composable
    private fun VehicleOnMap(insets: PaddingValues) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val top = minOf(insets.calculateTopPadding(), (maxHeight - MAP_CONTROLS - MARKER).coerceAtLeast(0.dp))
            Box(modifier = Modifier.fillMaxSize().padding(top = top, bottom = MAP_CONTROLS)) {
                Box(modifier = Modifier.align(Alignment.Center).size(MARKER).testTag(VEHICLE_TAG))
            }
        }
    }

    private fun messagesButton(language: Locale) =
        compose.onNodeWithText(text(language, R.string.hud_messages_count, KEPT).uppercase())

    /** The whole emergency banner, the live region holding the title, border included. */
    private fun emergencyBanner(language: Locale): Rect = compose
        .onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
                hasAnyDescendant(hasText(text(language, R.string.hud_link_lost_title).uppercase())),
            useUnmergedTree = true,
        )
        .fetchSemanticsNode()
        .boundsInRoot

    private fun lastDataBelowEmergency(language: Locale): Boolean {
        val lastData = lastData(language)
        return onScreen(lastData) && lastData.top >= emergency(language).bottom
    }

    private fun emergency(language: Locale) = bounds(text(language, R.string.hud_link_lost_title).uppercase(), language)

    private fun lastData(language: Locale) =
        bounds(text(language, R.string.hud_last_data, lostFor(language)).uppercase(), language)

    private fun since(language: Locale) =
        bounds(text(language, R.string.hud_link_lost_since, lostFor(language)), language)

    private fun otherVehicles(language: Locale) = localised(language).resources
        .getQuantityString(R.plurals.hud_other_vehicles, 1, OTHER_SYSTEM.toString())

    private fun lostFor(language: Locale) =
        text(language, DesignSystemR.string.elapsed_seconds, LOST_FOR.inWholeSeconds.toInt())

    /** Where the node with [text] is drawn, clipped by its parents; empty unless it is drawn whole. */
    private fun bounds(text: String, language: Locale): Rect {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode("$text in $language")
        val shown = node.boundsInRoot
        val whole = abs(shown.width - node.size.width) <= 1f && abs(shown.height - node.size.height) <= 1f
        return if (whole) shown else Rect.Zero
    }

    private fun onScreen(node: Rect): Boolean {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return !node.isEmpty && node.top >= root.top && node.bottom <= root.bottom && node.left >= root.left &&
            node.right <= root.right
    }

    private fun othersOverlapping(emergency: Rect): List<String> = compose
        .onAllNodes(HasText, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .filter { it.boundsInRoot.overlaps(emergency) && !emergency.containsAll(it.boundsInRoot) }
        .map { it.config.toString() }

    private fun largestText(): String = compose
        .onAllNodes(HasText, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.let { it.layoutInput.text.text to it.layoutInput.style.fontSize.value }
        }
        .maxBy { it.second }
        .first

    private fun Rect.containsAll(other: Rect) =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private fun text(language: Locale, id: Int, vararg args: Any): String = localised(language).getString(id, *args)

    private fun localised(language: Locale): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(language)
            },
        )
    }

    private companion object {
        const val KEPT = 9
        const val LARGE_FONT = 1.3f
        val SPANISH: Locale = Locale.forLanguageTag("es")
        val ENGLISH: Locale = Locale.ENGLISH
        val Upright = DpSize(width = 360.dp, height = 564.dp)
        val OnItsSide = DpSize(width = 640.dp, height = 300.dp)
        val LOST_FOR = 23.seconds
        const val VEHICLE_TAG = "vehicle"
        val MARKER = 60.dp

        /** The map's buttons and scale bar with their margin, once its attribution has folded. */
        val MAP_CONTROLS = 104.dp
        const val OTHER_SYSTEM = 2
        const val PORT = 14550
        val HasText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val REPLAY = ReplayBanner(recording = "flight.tlog", status = ReplayStatus.PLAYING)
        val VEHICLE = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Loiter"), armed = false)
        val CONNECTED = HudUiState.Connected(
            vehicle = VEHICLE,
            port = PORT,
            datagramCount = 42L,
            otherVehicles = listOf(OTHER_SYSTEM),
            linkUnstable = true,
        )
        val LOST = HudUiState.LinkLost(
            lastKnown = VEHICLE,
            sinceLastHeartbeat = LOST_FOR,
            otherVehicles = emptyList(),
            messages = VehicleMessages(
                kept = (1..KEPT).map {
                    MessageLine(
                        text = "PreArm: GPS 1: Bad fix $it",
                        level = AlertLevel.EMERGENCY,
                        unknownSeverity = null,
                        age = it.seconds,
                        incomplete = false,
                        recorded = false,
                    )
                },
            ),
        )
    }
}
