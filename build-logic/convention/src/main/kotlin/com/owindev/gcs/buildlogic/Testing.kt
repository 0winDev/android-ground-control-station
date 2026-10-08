package com.owindev.gcs.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.withType

internal const val TESTING_MODULE = ":core:testing"

/** JUnit 5 + MockK + Kluent + Turbine + coroutines-test for every module, plus the shared helpers. */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        "testImplementation"(platform(libs.library("junit-bom")))
        "testImplementation"(libs.library("junit-jupiter"))
        "testRuntimeOnly"(libs.library("junit-platform-launcher"))
        "testImplementation"(libs.library("mockk"))
        "testImplementation"(libs.library("kluent"))
        "testImplementation"(libs.library("turbine"))
        "testImplementation"(libs.library("kotlinx-coroutines-test"))
        if (path != TESTING_MODULE) {
            "testImplementation"(project(TESTING_MODULE))
        }
    }
}
