package com.owindev.gcs.di

import android.content.Context
import android.content.SharedPreferences
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.link.BackgroundLinkState
import com.owindev.gcs.theme.ThemePreferences
import com.owindev.gcs.theme.UiModeWindowNightMode
import com.owindev.gcs.theme.WindowNightMode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** The app's own settings and state, which no other module owns: display choices (REQ-030), the background link. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppModule {

    @Binds
    abstract fun bindWindowNightMode(impl: UiModeWindowNightMode): WindowNightMode

    @Binds
    abstract fun bindBackgroundLinkRepository(impl: BackgroundLinkState): BackgroundLinkRepository

    companion object {

        /** SharedPreferences, not Room (v0.5) or DataStore: one value, and no new dependency for it. */
        @Provides
        fun provideDisplayPreferences(@ApplicationContext context: Context): SharedPreferences =
            context.getSharedPreferences(ThemePreferences.FILE, Context.MODE_PRIVATE)
    }
}
