// Every module is configured through the gcs.* convention plugins in build-logic.
// The root project only applies the quality plugin, so its build scripts are checked too.
plugins {
    alias(libs.plugins.gcs.quality)
}

val buildLogic = gradle.includedBuild("build-logic")

// Run the same checks on the convention plugins themselves.
tasks.named("ktlintCheck") { dependsOn(buildLogic.task(":convention:ktlintCheck")) }
tasks.named("ktlintFormat") { dependsOn(buildLogic.task(":convention:ktlintFormat")) }
