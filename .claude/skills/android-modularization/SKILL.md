---
name: android-modularization
description: |
  Module layout, dependency rules and convention plugins for GCS (Ground Control Station).
  Trigger on: "add a module", "create a feature module", "how should I structure",
  "project structure", "convention plugin", "build-logic", "where does X live",
  "core module", "library module", "module boundaries", "verifyModuleGraph".
---

# Modular architecture — GCS

Authoritative sources: `settings.gradle.kts` (module list), `build-logic/convention/` (plugins) and
the allowlist in the `verifyModuleGraph` task (`build-logic/convention/.../ModuleGraph*.kt`).

## Module map

```
:app                  Android application. Composition root only: Application, MainActivity,
                      Hilt entry points, navigation. Depends on everything it wires.
:feature:hud          Android library + Compose. Telemetry HUD screen(s).
:feature:map          Android library + Compose. Map screen(s) (MapLibre arrives in v0.3).
:feature:mission      Android library + Compose. Mission editor / progress (v0.5).
:data:vehicle         Android library + Hilt. Implements :core:domain repositories on top of
                      :core:transport (UDP) and :core:mavlink (codec). Vehicle state per sysid,
                      link watchdog, command and mission protocol state machines.
:core:domain          Pure Kotlin (JVM). Domain models, repository interfaces, and the
                      safety-critical rules: mission validation, pre-flight checks, geo math,
                      data staleness.
:core:mavlink         Pure Kotlin (JVM). MAVLink v2 codec — test-first, owner reviews every line.
:core:transport       Pure Kotlin (JVM). UDP transport (DatagramSocket on Dispatchers.IO).
:core:designsystem    Android library + Compose. GcsTheme, colors, typography, sizes, shared components.
:core:testing         Pure Kotlin (JVM). Test helpers; only ever a testImplementation dependency.
```

## Dependency rules (enforced)

| Module | May depend on (production configurations) |
|---|---|
| `:feature:*` | `:core:domain`, `:core:designsystem` |
| `:data:vehicle` | `:core:domain`, `:core:mavlink`, `:core:transport` |
| `:core:domain`, `:core:mavlink`, `:core:transport`, `:core:designsystem`, `:core:testing` | nothing in this repo |
| `:app` | everything |
| any module (test configurations only) | `:core:testing` |

- Features never see the codec or the network, not even transitively: they talk to `:core:domain`
  interfaces, and `:app` binds those to `:data:vehicle` implementations with Hilt.
- Features never depend on each other.
- `:core:mavlink`'s runtime classpath may only contain the Kotlin standard library — no MAVLink
  libraries, no Android.
- `./gradlew verifyModuleGraph` (part of `check`, CI and pre-commit) fails on any violation. Never
  "fix" a violation by editing the allowlist without an explicit decision (and an ADR if it changes
  the architecture).

## Convention plugins

Every `build.gradle.kts` applies one or two `gcs.*` plugins and declares only its own dependencies.

| Plugin | Applies |
|---|---|
| `gcs.android.application` | Android app + Compose + Hilt + quality, JUnit 5 test stack |
| `gcs.android.library` | Android library + quality, JUnit 5 test stack |
| `gcs.android.library.compose` | `gcs.android.library` + Compose |
| `gcs.android.feature` | `gcs.android.library.compose` + Hilt + `:core:domain` + `:core:designsystem` |
| `gcs.jvm.library` | Kotlin JVM + Android Lint + quality, JUnit 5 test stack; JDK 21 toolchain, bytecode/API 17 |
| `gcs.hilt` | Hilt + KSP |
| `gcs.quality` | detekt + ktlint |
| `gcs.kover` | Kover coverage (`:core:mavlink`, `:core:domain`) |

Example (`feature/hud/build.gradle.kts`):

```kotlin
plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.hud"
}
```

## Checklist — adding a module

- [ ] Agreed in the issue/plan first (a new module is an architecture change).
- [ ] Registered in `settings.gradle.kts`; directory `group/name`, namespace `com.owindev.gcs.<group>.<name>`.
- [ ] Applies the right `gcs.*` plugin; no plugin configuration copied into the module.
- [ ] Added to the `verifyModuleGraph` allowlist with its permitted dependencies.
- [ ] Has at least one test.
- [ ] README architecture diagram updated.
