import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.gcs.jvm.library)
    alias(libs.plugins.gcs.kover)
}

// The MAVLink codec is our own and must stay pure Kotlin: no dependencies at all.

// Coverage minimum agreed in #8 for the codec; `koverVerify` (part of `check`) fails below it.
kover {
    reports {
        verify {
            rule {
                minBound(90, CoverageUnit.LINE)
                minBound(80, CoverageUnit.BRANCH)
            }
        }
    }
}
