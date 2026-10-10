plugins {
    alias(libs.plugins.gcs.jvm.library)
    alias(libs.plugins.gcs.kover)
}

dependencies {
    // Repository contracts expose StateFlow, so features need coroutines on their compile classpath.
    api(libs.kotlinx.coroutines.core)
}
