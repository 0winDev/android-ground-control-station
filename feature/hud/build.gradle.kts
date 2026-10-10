plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.hud"
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
