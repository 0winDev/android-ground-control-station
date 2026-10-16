package com.owindev.gcs.theme

import androidx.lifecycle.ViewModel
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The palette the app is drawn in and the operator's switch between night and day (REQ-030). */
@HiltViewModel
class ThemeViewModel @Inject constructor(private val preferences: ThemePreferences) : ViewModel() {

    val palette: StateFlow<GcsPalette> = preferences.palette

    /** One tap switches at once: night to day, day to night. */
    fun onThemeToggled() {
        preferences.set(if (palette.value == GcsPalette.NIGHT) GcsPalette.DAY else GcsPalette.NIGHT)
    }
}
