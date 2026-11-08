package com.owindev.gcs

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.link.BackgroundLinkState
import com.owindev.gcs.link.BackgroundLinkViewModel
import com.owindev.gcs.link.LinkNotification
import com.owindev.gcs.link.VehicleLinkService
import com.owindev.gcs.navigation.FlightGuardViewModel
import com.owindev.gcs.navigation.GcsNavigation
import com.owindev.gcs.navigation.shareRecording
import com.owindev.gcs.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels()
    private val flightGuard: FlightGuardViewModel by viewModels()
    private val backgroundLink: BackgroundLinkViewModel by viewModels()

    @Inject
    lateinit var backgroundLinkState: BackgroundLinkState

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshBackgroundLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stored palette is already read (ThemePreferences), so the bars are right from the first frame.
        enableSystemBars(themeViewModel.palette.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { backgroundLink.asksNotifications.collect { askNotificationsOnce() } }
                backgroundLink.starts.collect { startVehicleLinkService() }
            }
        }
        setContent {
            val palette by themeViewModel.palette.collectAsStateWithLifecycle()
            DisposableEffect(palette) {
                enableSystemBars(palette)
                onDispose {}
            }
            GcsTheme(palette = palette) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GcsNavigation(
                        onToggleTheme = themeViewModel::onThemeToggled,
                        guard = flightGuard,
                        onShare = ::shareRecording,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    // Not only on start: in split screen the system's settings change beside the app while it stays started.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshBackgroundLink()
    }

    override fun onStart() {
        super.onStart()
        refreshBackgroundLink()
    }

    private fun refreshBackgroundLink() {
        if (backgroundLinkState.refresh(this)) postLinkNotificationAgain()
    }

    /** Asked when the service first starts (REQ-046); denied, the service runs with its notification hidden. */
    private fun askNotificationsOnce() {
        if (backgroundLinkState.notificationsAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backgroundLinkState.notificationsAsked = true
        if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(POST_NOTIFICATIONS)
        }
    }

    private fun postLinkNotificationAgain() {
        try {
            startService(Intent(this, VehicleLinkService::class.java).setAction(LinkNotification.ACTION_POST_AGAIN))
        } catch (e: IllegalStateException) {
            Log.w("MainActivity", "The vehicle link notification could not be posted again", e)
        }
    }

    /** Refused on the edge of leaving the foreground (API 31+): the next start, by level, tries again. */
    private fun startVehicleLinkService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, VehicleLinkService::class.java))
        } catch (e: IllegalStateException) {
            Log.w("MainActivity", "The vehicle link service could not start", e)
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
