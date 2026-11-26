plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.hud"
}

// SpanishLevelWordsTest reads this file directly: without it as an input, a cached result hides a changed word.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/res/values-es/strings.xml").withPathSensitivity(PathSensitivity.RELATIVE)
}
