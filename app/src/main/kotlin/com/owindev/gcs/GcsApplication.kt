package com.owindev.gcs

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's root. The `:core:domain` contracts are bound in `:data:vehicle` (`VehicleDataModule`) and the app's own
 * settings in `AppModule`.
 */
@HiltAndroidApp
class GcsApplication : Application()
