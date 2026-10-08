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
        }
        compileOptions {
            sourceCompatibility = javaTarget
            targetCompatibility = javaTarget
        }
    }
}
