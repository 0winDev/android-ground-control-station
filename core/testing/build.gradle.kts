plugins {
    alias(libs.plugins.gcs.jvm.library)
}

// Shared test helpers. Every module gets this as testImplementation from the convention plugins;
// it must never be a production dependency.
dependencies {
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
    api(libs.mockk)
    api(libs.kotlinx.coroutines.test)
}
