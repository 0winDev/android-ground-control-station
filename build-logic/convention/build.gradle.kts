plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

group = "com.owindev.gcs.buildlogic"

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

ktlint {
    version.set(libs.versions.ktlint)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "gcs.android.application"
            implementationClass = "com.owindev.gcs.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "gcs.android.library"
            implementationClass = "com.owindev.gcs.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "gcs.android.library.compose"
            implementationClass = "com.owindev.gcs.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "gcs.android.feature"
            implementationClass = "com.owindev.gcs.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "gcs.jvm.library"
            implementationClass = "com.owindev.gcs.buildlogic.JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "gcs.quality"
            implementationClass = "com.owindev.gcs.buildlogic.QualityConventionPlugin"
        }
        register("kover") {
            id = "gcs.kover"
            implementationClass = "com.owindev.gcs.buildlogic.KoverConventionPlugin"
        }
        register("hilt") {
            id = "gcs.hilt"
            implementationClass = "com.owindev.gcs.buildlogic.HiltConventionPlugin"
        }
    }
}
