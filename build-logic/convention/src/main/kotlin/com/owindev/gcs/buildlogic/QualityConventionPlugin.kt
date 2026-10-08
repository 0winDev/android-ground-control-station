package com.owindev.gcs.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * detekt (with the Compose rules, without detekt-formatting) and ktlint. Applied to every module and
 * to the root project (for its build scripts).
 */
class QualityConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                parallel = true
            }
            tasks.withType<Detekt>().configureEach {
                jvmTarget = javaTarget.toString()
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(false)
                    md.required.set(false)
                    txt.required.set(false)
                }
            }
            // detekt 1.23.x embeds the Kotlin compiler it was built with; keep the project's newer
            // Kotlin version off detekt's own classpath.
            configurations.matching { it.name == "detekt" }.configureEach {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") {
                        useVersion(libs.version("detektKotlin"))
                    }
                }
            }
            dependencies {
                "detektPlugins"(libs.library("compose-rules-detekt"))
            }

            extensions.configure<KtlintExtension> {
                version.set(libs.version("ktlint"))
                android.set(true)
            }
        }
    }
}
