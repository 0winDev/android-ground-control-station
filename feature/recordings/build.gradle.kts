plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.recordings"
}

dependencies {
    // The system file picker (SAF) for imports.
    implementation(libs.androidx.activity.compose)
}
