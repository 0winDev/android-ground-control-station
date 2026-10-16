package com.owindev.gcs.di

import android.content.Context
import android.content.SharedPreferences
import com.owindev.gcs.theme.ThemePreferences
import com.owindev.gcs.theme.UiModeWindowNightMode
import com.owindev.gcs.theme.WindowNightMode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** The app's own settings, which no other module needs: the operator's display choices (REQ-030). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppModule {

    @Binds
    abstract fun bindWindowNightMode(impl: UiModeWindowNightMode): WindowNightMode

    companion object {

        /** SharedPreferences, not Room (v0.5) or DataStore: one value, and no new dependency for it. */
        @Provides
        fun provideDisplayPreferences(@ApplicationContext context: Context): SharedPreferences =
            context.getSharedPreferences(ThemePreferences.FILE, Context.MODE_PRIVATE)
    }
}
