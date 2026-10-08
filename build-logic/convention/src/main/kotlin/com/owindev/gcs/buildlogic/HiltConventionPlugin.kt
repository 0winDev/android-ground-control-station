package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt with KSP. Only for Android modules; pure JVM modules have no DI annotations. */
class HiltConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                "implementation"(libs.library("hilt-android"))
                "ksp"(libs.library("hilt-compiler"))
            }
        }
    }
}
