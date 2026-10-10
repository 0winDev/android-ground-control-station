package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Test coverage for the modules where it matters most: the MAVLink codec and the
 * safety-critical domain rules. Reports: `koverHtmlReport` / `koverXmlReport`.
 * No minimum is enforced while the modules are still empty; one is agreed in v0.1.
 */
class KoverConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
    }
}
