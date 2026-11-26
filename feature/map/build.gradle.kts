plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.map"
}

dependencies {
    implementation(libs.maplibre.compose)
    runtimeOnly(libs.maplibre.compose.runtime.opengl)
}

// NightStyleTest reads the styles directly: without them as an input, a cached result hides a changed style.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
}
