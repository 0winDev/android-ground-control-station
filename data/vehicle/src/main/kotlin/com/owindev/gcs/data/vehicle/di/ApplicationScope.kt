package com.owindev.gcs.data.vehicle.di

import javax.inject.Qualifier

/** The [kotlinx.coroutines.CoroutineScope] that lives as long as the app process. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
