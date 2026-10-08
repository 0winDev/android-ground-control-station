package com.owindev.gcs.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** The app module: Android application + Compose + Hilt + the test stack. */
class AndroidApplicationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("gcs.hilt")
            configureAndroidApplication()
            configureKotlin()
            addComposeDependencies()
            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
                lint {
                    // Also lint the modules the app depends on, including the pure JVM ones.
                    checkDependencies = true
                    // Dependency versions are Dependabot's job; this check also hits the network on every run.
                    disable += "NewerVersionAvailable"
                }
            }
            dependencies {
                "implementation"(libs.library("androidx-activity-compose"))
            }
            configureUnitTests()
            configureModuleGraphCheck()
            pluginManager.apply("gcs.quality")
        }
    }
}
