plugins {
    alias(libs.plugins.gcs.android.library)
    alias(libs.plugins.gcs.hilt)
}

android {
    namespace = "com.owindev.gcs.data.vehicle"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.mavlink)
    implementation(projects.core.transport)
}
