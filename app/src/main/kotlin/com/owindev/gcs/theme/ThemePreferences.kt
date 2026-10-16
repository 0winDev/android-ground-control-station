package com.owindev.gcs.theme

import android.content.SharedPreferences
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The palette the operator chose (REQ-030), remembered across launches; night until they choose day, and night for
 * anything stored that is not a palette.
 *
 * Read synchronously when created, before the first frame: it is one value in a tiny file, and reading it later
 * would draw the first frame at night and then switch. Each choice is also handed to [windowNightMode], so the
 * window the system draws before the app does starts in the same palette.
 */
@Singleton
class ThemePreferences @Inject constructor(
    private val preferences: SharedPreferences,
    private val windowNightMode: WindowNightMode,
) {

    private val current = MutableStateFlow(stored())

    val palette: StateFlow<GcsPalette> = current.asStateFlow()

    init {
        // Also on the first launch and after a restore, so the window follows what is stored.
        windowNightMode.apply(current.value)
    }

    fun set(palette: GcsPalette) {
        preferences.edit().putString(KEY_PALETTE, palette.name).apply()
        current.value = palette
        windowNightMode.apply(palette)
    }

    private fun stored(): GcsPalette = preferences.getString(KEY_PALETTE, null)
        ?.let { name -> GcsPalette.entries.firstOrNull { it.name == name } }
        ?: GcsPalette.NIGHT

    companion object {
        /** The private preferences file that holds the display choices. */
        const val FILE = "gcs_display"

        private const val KEY_PALETTE = "palette"
    }
}
