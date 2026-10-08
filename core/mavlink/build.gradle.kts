plugins {
    alias(libs.plugins.gcs.jvm.library)
}

// The MAVLink codec is hand-written and must stay pure Kotlin: no dependencies at all.
