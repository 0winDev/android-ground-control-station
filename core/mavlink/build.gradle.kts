plugins {
    alias(libs.plugins.gcs.jvm.library)
    alias(libs.plugins.gcs.kover)
}

// The MAVLink codec is hand-written and must stay pure Kotlin: no dependencies at all.
