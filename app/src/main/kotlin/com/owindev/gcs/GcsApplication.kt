package com.owindev.gcs

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt composition root: `:app` wires the `:core:domain` contracts to their `:data:vehicle` implementations. */
@HiltAndroidApp
class GcsApplication : Application()
