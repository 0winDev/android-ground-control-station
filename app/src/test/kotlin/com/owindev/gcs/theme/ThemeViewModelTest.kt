package com.owindev.gcs.theme

import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("REQ-030")
class ThemeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val palette = MutableStateFlow(GcsPalette.NIGHT)
    private val preferences: ThemePreferences = mockk()

    private lateinit var viewModel: ThemeViewModel

    @BeforeEach
    fun setUp() {
        every { preferences.palette } returns palette
        viewModel = ThemeViewModel(preferences)
    }

    @Test
    fun `GIVEN night WHEN the operator toggles the theme THEN day is stored`() {
        every { preferences.set(any()) } just runs

        viewModel.onThemeToggled()

        verifyOnce {
            preferences.set(GcsPalette.DAY)
        }
    }

    @Test
    fun `GIVEN day WHEN the operator toggles the theme THEN night is stored`() {
        palette.value = GcsPalette.DAY
        every { preferences.set(any()) } just runs

        viewModel.onThemeToggled()

        verifyOnce {
            preferences.set(GcsPalette.NIGHT)
        }
    }
}
