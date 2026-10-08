plugins {
    alias(libs.plugins.gcs.android.application)
}

android {
    namespace = "com.owindev.gcs"

    defaultConfig {
        applicationId = "com.owindev.gcs"
        versionCode = 1
        versionName = "0.0.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.domain)
    implementation(projects.data.vehicle)
    implementation(projects.feature.hud)
    implementation(projects.feature.map)
    implementation(projects.feature.mission)
}
