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
        "androidTestImplementation"(platform(libs.library("androidx-compose-bom")))
        "androidTestImplementation"(libs.library("androidx-compose-ui-test-junit4"))
        "androidTestImplementation"(libs.library("androidx-test-runner"))
        "androidTestImplementation"(libs.library("kluent-android"))
        // In the test APK only: in debug it would export its empty activity from the app (docs/security.md, P-1).
        "androidTestImplementation"(libs.library("androidx-compose-ui-test-manifest"))
    }
}
