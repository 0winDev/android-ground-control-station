package com.owindev.gcs.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.feature.hud.HudScreen
import com.owindev.gcs.feature.map.MapScreen
import com.owindev.gcs.feature.recordings.RecordingsScreen
import java.io.File

/**
 * The app's screens and how the operator moves between them (ADR 0007). The flight screen is the home; the recordings
 * open over it only while [guard] allows it, and the app goes back to the flight screen on its own when a live vehicle
 * arms or a new live emergency is raised, so nothing hides an emergency for longer than a frame.
 */
@Composable
internal fun GcsNavigation(
    palette: GcsPalette,
    onToggleTheme: () -> Unit,
    guard: FlightGuardViewModel,
    onShare: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf(Destination.FLIGHT) }
    val mayLeaveFlight by guard.mayLeaveFlight.collectAsStateWithLifecycle()
    LaunchedEffect(mayLeaveFlight) { if (!mayLeaveFlight) backStack.backToFlight() }
    LaunchedEffect(guard) { guard.returnToFlight.collect { backStack.backToFlight() } }
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.backToFlight() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { destination ->
            NavEntry(destination) {
                when (destination) {
                    Destination.FLIGHT -> HudScreen(
                        palette = palette,
                        onToggleTheme = onToggleTheme,
                        onOpenRecordings = if (mayLeaveFlight) ({ backStack.openRecordings() }) else null,
                        map = { insets -> MapScreen(palette = palette, viewportInsets = insets) },
                    )

                    Destination.RECORDINGS -> RecordingsScreen(
                        onBack = { backStack.backToFlight() },
                        onReplayStart = { backStack.backToFlight() },
                        onShare = onShare,
                    )
                }
            }
        },
    )
}
