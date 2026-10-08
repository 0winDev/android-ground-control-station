package com.owindev.gcs.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Pure Kotlin module (no Android). Compiled with the JDK 21 toolchain but against the Java 17 API
 * (`-Xjdk-release` / `release`), so a JDK-21-only API fails at compile time instead of on a device.
 * Android Lint is applied so that the app's lint run also checks these modules.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("com.android.lint")
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(jdkToolchain))
            }
            configureKotlin()
            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions.freeCompilerArgs.add("-Xjdk-release=${javaTarget.majorVersion}")
            }
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(javaTarget.majorVersion.toInt())
            }
            configureUnitTests()
            configureModuleGraphCheck()
            pluginManager.apply("gcs.quality")
        }
    }
}
