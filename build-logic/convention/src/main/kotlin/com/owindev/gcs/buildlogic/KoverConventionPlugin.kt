package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Test coverage for the modules where it matters most: the MAVLink codec and the
 * safety-critical domain rules. Reports: `koverHtmlReport` / `koverXmlReport`.
 * Minimums live in each module's build file: `:core:mavlink` enforces one (`koverVerify`);
 * `:core:domain` gets its own once it has code.
 */
class KoverConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
    }
}
