package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Android library + the test stack. */
class AndroidLibraryConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            configureAndroidLibrary()
            configureKotlin()
            configureUnitTests()
        }
    }
}
