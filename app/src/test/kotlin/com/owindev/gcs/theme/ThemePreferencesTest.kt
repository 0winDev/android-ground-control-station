package com.owindev.gcs.theme

import android.content.SharedPreferences
import com.owindev.gcs.core.designsystem.theme.GcsPalette
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The stored value is read when the preferences are created, so tests that store one create their own. */
@Tag("REQ-030")
class ThemePreferencesTest {

    private val preferences: SharedPreferences = mockk()
    private val editor: SharedPreferences.Editor = mockk()
    private val windowNightMode: WindowNightMode = mockk()

    private lateinit var themePreferences: ThemePreferences

    @BeforeEach
    fun setUp() {
        every { preferences.getString(KEY, null) } returns null
        every { windowNightMode.apply(any()) } just runs
        themePreferences = ThemePreferences(preferences, windowNightMode)
    }

    @Test
    fun `GIVEN nothing stored WHEN reading the palette THEN is night`() {
        val expected = GcsPalette.NIGHT

        val result = themePreferences.palette.value

        result shouldBeEqualTo expected
    }

    @Test
    fun `GIVEN day stored WHEN the preferences are created THEN the palette and the window are day`() {
        every { preferences.getString(KEY, null) } returns DAY

        val result = ThemePreferences(preferences, windowNightMode).palette.value

        result shouldBeEqualTo GcsPalette.DAY
        verifyOnce {
            windowNightMode.apply(GcsPalette.DAY)
        }
    }

    @Test
    fun `GIVEN something stored that is not a palette WHEN the preferences are created THEN the palette is night`() {
        every { preferences.getString(KEY, null) } returns UNKNOWN

        val result = ThemePreferences(preferences, windowNightMode).palette.value

        result shouldBeEqualTo GcsPalette.NIGHT
    }

    @Test
    fun `GIVEN night WHEN setting day THEN is stored, emitted and handed to the window`() {
        every { preferences.edit() } returns editor
        every { editor.putString(KEY, DAY) } returns editor
        every { editor.apply() } just runs

        themePreferences.set(GcsPalette.DAY)
        val result = themePreferences.palette.value

        result shouldBeEqualTo GcsPalette.DAY
        verifyOnce {
            editor.putString(KEY, DAY)
            editor.apply()
            windowNightMode.apply(GcsPalette.DAY)
        }
    }
}

private const val KEY = "palette"
private const val DAY = "DAY"
private const val UNKNOWN = "DUSK"
