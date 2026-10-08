package com.owindev.gcs.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * The architecture rules, as data. Dependencies only flow one way:
 * features → :core:domain + :core:designsystem; :data:vehicle → :core:domain + :core:mavlink +
 * :core:transport; every other :core module → nothing. :app wires everything.
 * Changing this allowlist is an architecture decision (ADR), not a build fix.
 */
internal object ModuleGraphRules {

    private const val CODEC_MODULE = ":core:mavlink"

    /** Only the Kotlin standard library may end up in the codec's runtime classpath. */
    val codecAllowedExternalModules = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )

    /** `null` means the module may depend on any module (the composition root). */
    fun allowedProductionDependencies(modulePath: String): Set<String>? = when {
        modulePath == ":app" -> null
        modulePath.startsWith(":feature:") -> setOf(":core:domain", ":core:designsystem")
        modulePath == ":data:vehicle" -> setOf(":core:domain", ":core:mavlink", ":core:transport")
        else -> emptySet()
    }

    /** Test source sets may additionally use the shared test helpers. */
    val allowedTestOnlyDependencies = setOf(TESTING_MODULE)

    fun isCodec(modulePath: String): Boolean = modulePath == CODEC_MODULE
}

/** Fails when a module declares a dependency the architecture does not allow. */
abstract class VerifyModuleGraphTask : DefaultTask() {

    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val productionDependencies: SetProperty<String>

    @get:Input
    abstract val testDependencies: SetProperty<String>

    /** Resolved external modules of the runtime classpath; only checked for the codec. */
    @get:Input
    abstract val externalRuntimeModules: SetProperty<String>

    @TaskAction
    fun verify() {
        val path = modulePath.get()
        val allowed = ModuleGraphRules.allowedProductionDependencies(path)
        val violations = buildList {
            if (allowed != null) {
                (productionDependencies.get() - allowed).forEach {
                    add("$path → $it (allowed: ${allowed.ifEmpty { "none" }})")
                }
                (testDependencies.get() - allowed - ModuleGraphRules.allowedTestOnlyDependencies)
                    .forEach { add("$path (tests) → $it") }
            }
            if (ModuleGraphRules.isCodec(path)) {
                (externalRuntimeModules.get() - ModuleGraphRules.codecAllowedExternalModules)
                    .forEach { add("$path must stay pure Kotlin, but its runtime classpath contains $it") }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module graph violations (see the android-modularization skill):\n" +
                    violations.joinToString(separator = "\n") { "  - $it" },
            )
        }
    }
}

internal fun Project.configureModuleGraphCheck() {
    // Inside the task's configuration block, `path` would be the task's path, not the module's.
    val projectPath = path
    val verify = tasks.register<VerifyModuleGraphTask>("verifyModuleGraph") {
        group = "verification"
        description = "Checks that this module only depends on the modules the architecture allows."
        modulePath.set(projectPath)
        productionDependencies.set(provider { declaredProjectDependencies(testConfigurations = false) })
        testDependencies.set(provider { declaredProjectDependencies(testConfigurations = true) })
        if (ModuleGraphRules.isCodec(projectPath)) {
            externalRuntimeModules.set(
                configurations.named("runtimeClasspath").flatMap { classpath ->
                    classpath.incoming.resolutionResult.rootComponent.map(::collectExternalModules)
                },
            )
        } else {
            externalRuntimeModules.set(emptySet())
        }
    }
    tasks.named("check") {
        dependsOn(verify)
    }
}

private fun Project.declaredProjectDependencies(testConfigurations: Boolean): Set<String> =
    configurations
        .filter { it.name.contains("test", ignoreCase = true) == testConfigurations }
        .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java) }
        .map { it.path }
        .filterNot { it == path }
        .toSet()

private fun collectExternalModules(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (seen.add(component)) {
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { pending.addLast(it.selected) }
        }
    }
    return seen
        .mapNotNull { it.id as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}" }
        .toSet()
}
