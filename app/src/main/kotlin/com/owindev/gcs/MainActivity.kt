package com.owindev.gcs

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.navigation.FlightGuardViewModel
import com.owindev.gcs.navigation.GcsNavigation
import com.owindev.gcs.navigation.shareRecording
import com.owindev.gcs.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels()
    private val flightGuard: FlightGuardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stored palette is already read (ThemePreferences), so the bars are right from the first frame.
        enableSystemBars(themeViewModel.palette.value)
        setContent {
            val palette by themeViewModel.palette.collectAsStateWithLifecycle()
            DisposableEffect(palette) {
                enableSystemBars(palette)
                onDispose {}
            }
            GcsTheme(palette = palette) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GcsNavigation(
                        palette = palette,
                        onToggleTheme = themeViewModel::onThemeToggled,
                        guard = flightGuard,
                        onShare = ::shareRecording,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    /**
     * Switching palette changes the app's night mode (WindowNightMode), handled here without recreating the activity
     * (`configChanges="uiMode"`); the window then resets the status bar's icons once the change is dispatched, so the
     * bars are set again after it, on the next pass of the view's loop.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { enableSystemBars(themeViewModel.palette.value) }
    }

    /**
     * The system bars follow the app's palette, not the system's theme: light icons at night, dark icons by day,
     * on a transparent bar so the HUD's background shows through.
     */
    private fun enableSystemBars(palette: GcsPalette) {
        val style = if (palette == GcsPalette.DAY) {
            SystemBarStyle.light(scrim = Color.TRANSPARENT, darkScrim = Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(scrim = Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
