package com.owindev.gcs.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.configureAndroidApplication() {
    extensions.configure<ApplicationExtension> {
        compileSdk = intVersion("compileSdk")
        defaultConfig {
            minSdk = intVersion("minSdk")
            targetSdk = intVersion("targetSdk")
        }
        compileOptions {
            sourceCompatibility = javaTarget
            targetCompatibility = javaTarget
        }
    }
}

internal fun Project.configureAndroidLibrary() {
    extensions.configure<LibraryExtension> {
        compileSdk = intVersion("compileSdk")
        defaultConfig {
            minSdk = intVersion("minSdk")
            testInstrumentationRunner = INSTRUMENTATION_RUNNER
        }
        compileOptions {
            sourceCompatibility = javaTarget
            targetCompatibility = javaTarget
        }
    }
}

/** Runs the instrumented Compose UI tests on a device (`connectedDebugAndroidTest`); CI does not run them. */
private const val INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
