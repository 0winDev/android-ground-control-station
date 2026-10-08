package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * A feature module: Compose + Hilt, and the only two modules a feature may depend on.
 * Features never see the codec, the network or another feature.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("gcs.android.library.compose")
            pluginManager.apply("gcs.hilt")
            dependencies {
                "implementation"(project(":core:domain"))
                "implementation"(project(":core:designsystem"))
            }
        }
    }
}
