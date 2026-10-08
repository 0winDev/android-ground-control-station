package com.owindev.gcs.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** JDK used to compile and run tests. */
internal val Project.jdkToolchain: Int
    get() = intVersion("jdkToolchain")

/**
 * Bytecode level for every module. 17 is the highest Java version the Android docs list as
 * supported ("Java versions in Android builds"), and the JVM modules are consumed by Android.
 */
internal val Project.javaTarget: JavaVersion
    get() = JavaVersion.toVersion(libs.version("jvmTarget"))

internal fun Project.configureKotlin() {
    extensions.configure<KotlinProjectExtension> {
        jvmToolchain(jdkToolchain)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaTarget.toString()))
            allWarningsAsErrors.set(true)
        }
    }
}
