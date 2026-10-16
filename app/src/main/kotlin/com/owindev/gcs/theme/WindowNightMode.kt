package com.owindev.gcs.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Tells the system which palette the app is in, so the window it draws before the app does (the starting window,
 * whose background is `gcs_window_background`: night by default, day under `values-notnight`) is already in the
 * operator's palette and a day launch does not flash dark.
 *
 * Only from Android 12 (API 31), which has a per-app night mode that the system remembers. Before that the starting
 * window always uses the night background, so a day launch shows it for an instant: a known limit.
 */
fun interface WindowNightMode {

    fun apply(palette: GcsPalette)
}

class UiModeWindowNightMode @Inject constructor(@ApplicationContext private val context: Context) : WindowNightMode {

    override fun apply(palette: GcsPalette) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = if (palette == GcsPalette.DAY) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }
}
