package com.owindev.gcs.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.addComposeDependencies() {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    dependencies {
        "implementation"(platform(libs.library("androidx-compose-bom")))
        "implementation"(libs.library("androidx-compose-ui"))
        "implementation"(libs.library("androidx-compose-material3"))
        "implementation"(libs.library("androidx-compose-ui-tooling-preview"))
        "debugImplementation"(libs.library("androidx-compose-ui-tooling"))
    }
}
