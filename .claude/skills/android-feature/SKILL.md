---
name: android-feature
description: Create or modify a feature in GCS (Ground Control Station) following the repository architecture. Use when adding a screen to :feature:hud/:feature:map/:feature:mission, creating a new feature module, or deciding where new code (ViewModel, UiState, use case, repository, protocol logic, DI) belongs.
---

# Android feature anatomy — GCS

First read `CLAUDE.md` (source of truth) and the `android-modularization` skill.

The first screen is the HUD (`:feature:hud`, issue #6): use it as the reference implementation.
`:feature:map` and `:feature:mission` have no screens yet.

## Where code goes

| Code | Module | Why |
|---|---|---|
| Screen, `UiState`, ViewModel | `:feature:<name>` | Presentation only |
| Domain models (vehicle state, mission, waypoint, geo point), repository **interfaces** | `:core:domain` | Shared contract, pure Kotlin |
| Safety-critical rules: mission validation, pre-flight checks, geo math, data staleness | `:core:domain` | Pure Kotlin, fast tests, coverage, fuzzing |
| Use cases (only when they add logic beyond forwarding a repository call) | `:core:domain` | Same |
| Repository **implementations**, MAVLink ↔ domain mapping, link watchdog, command retries, mission upload state machine | `:data:vehicle` | Needs protocol + transport |
| Frame/CRC/parse/sign | `:core:mavlink` | **Hand-written by the owner — never write it** |
| UDP socket | `:core:transport` | |
| Theme, shared components | `:core:designsystem` | |
| Hilt binding interface → implementation | `:data:vehicle` (`di/`) | Installed in `SingletonComponent`, reached through `:app` |

Rules:

- A feature only imports `com.owindev.gcs.core.domain.*` and `com.owindev.gcs.core.designsystem.*`.
  Never a MAVLink type, never a socket. `verifyModuleGraph` fails the build otherwise.
- No protocol logic in ViewModels: a ViewModel maps domain state to `UiState` and forwards intents.
- Dangerous commands (arm, takeoff, mission change in flight) require an explicit confirmation step
  modelled in `UiState`; the ViewModel only calls the domain after the confirmation intent.
- Stale data is visible: every telemetry value shown carries its age (from the domain), and the UI
  greys it out past the threshold.

## Files per screen

```
feature/hud/src/main/kotlin/com/owindev/gcs/feature/hud/
├── HudUiState.kt      sealed interface / data class, immutable
├── HudViewModel.kt    @HiltViewModel, exposes val state: StateFlow<HudUiState>
└── HudScreen.kt       @Composable HudScreen(...) collecting state + stateless HudContent(...)
feature/hud/src/main/res/values{,-es}/strings.xml
feature/hud/src/test/kotlin/com/owindev/gcs/feature/hud/HudViewModelTest.kt
```

See `android-mvi` for the ViewModel/UiState shape and `compose-ui` for design-system usage.

## Build file

```kotlin
plugins {
    alias(libs.plugins.gcs.android.feature)
}

android {
    namespace = "com.owindev.gcs.feature.hud"
}
```

`gcs.android.feature` already adds Compose, Hilt, `:core:domain`, `:core:designsystem` and the test
stack. Add only what this feature needs beyond that, through the version catalog.

## Workflow

1. Read the issue (`gh issue view <n>`): requirements (REQ-xxx) and pitfalls.
2. Put domain rules and their tests in `:core:domain` first, then the data side, then the UI
   (risk order: protocol and safety → data → UI).
3. Strings in `values/strings.xml` (English) **and** `values-es/strings.xml` (Spanish).
4. Every ViewModel, use case and repository behavior ships with tests (`android-tests`), tagged with
   the REQ it covers.
5. Verify narrowly (`./gradlew :feature:hud:testDebugUnitTest`), then the full gate:
   `./gradlew ktlintCheck detekt verifyModuleGraph lintDebug test assembleDebug`.
6. Visible change → check on device/emulator (`android-cli`) and add screenshots to the PR.
   Protocol/telemetry/commands → `sitl-verify`.

## Output expectations

Summarize: behavior changed, modules/files touched, REQs covered, verification run and anything that
could not be verified.
