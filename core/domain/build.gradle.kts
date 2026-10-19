import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.gcs.jvm.library)
    alias(libs.plugins.gcs.kover)
}

dependencies {
    // Repository contracts expose StateFlow, so features need coroutines on their compile classpath.
    api(libs.kotlinx.coroutines.core)
}

// Coverage minimum agreed in #63 for the safety-critical rules, measured at 94.5 % lines and 97.5 % branches with a
// margin for refactors; `koverVerify` (part of `check`) fails below it.
kover {
    reports {
        verify {
            rule {
                minBound(92, CoverageUnit.LINE)
                minBound(95, CoverageUnit.BRANCH)
            }
        }
    }
}
