---
name: android-mvi
description: State-driven presentation pattern for GCS (Ground Control Station) Compose screens. Use when creating a new screen or feature presentation layer, adding user interactions (including dangerous commands that need confirmation), or reviewing presentation code for UiState/ViewModel/Screen conventions.
---

# Presentation pattern — GCS

The reference implementation is the HUD in `:feature:hud` (`HudUiState`, `HudViewModel`, `HudScreen`); its commands
(`HudCommandsViewModel`, `HudCommands.kt`, `HudCommandSheet.kt`) are the reference for dangerous commands (#104).

## Files per screen

| File | Contains |
|---|---|
| `<Screen>UiState.kt` | Immutable state: a `sealed interface` (`Loading`, `Disconnected`, `Content`, …) or a `data class` |
| `<Screen>ViewModel.kt` | `@HiltViewModel class <Screen>ViewModel @Inject constructor(...) : ViewModel()` exposing `val state: StateFlow<<Screen>UiState>` |
| `<Screen>Screen.kt` | `@Composable <Screen>Screen(...)` that collects state, plus a stateless `<Screen>Content(...)` |
| `<Screen>Effect.kt` | Only when a real one-off event exists (see below) |

## State

- Exposed as `StateFlow`, built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`
  from domain flows, or a private `MutableStateFlow` + `asStateFlow()` for action-driven screens.
- **No protocol logic in the ViewModel.** It maps domain models to UI models (formatting, units,
  staleness flags provided by the domain) and forwards intents to the domain.
- Telemetry values carry their **age**; the UiState exposes whether each value is stale so the UI can
  grey it out. The staleness rule itself lives in `:core:domain`.
- **Link loss is state, not an event**: `LinkLost(lastKnownPosition, since)` keeps showing until the
  link is back.

## Intents

Public ViewModel functions are the intents (`onCommandRequested(command)`, `onCommandConfirmed(key)`,
`onCommandCancelled()`). No `onEvent(Event)` dispatcher unless a screen genuinely benefits from it.

### Dangerous commands: request, then slide (REQ-050)

Arm, take off, RTL and land never fire on a tap. A request opens the confirmation sheet; only the slide sends:

```kotlin
fun onCommandRequested(command: VehicleCommand) {
    val key = commands.commandKey.value ?: return
    if (!canConfirm(command)) return                  // judged on fresh values, not the state last drawn
    request.value = Request(command, openedOn = key, altitudeMetres = ...)
}

fun onCommandConfirmed(key: ConfirmKey) {             // the key the slide started on
    val confirming = request.value?.takeIf { canConfirm(it.command) } ?: return
    if (key != confirming.confirmKey()) request.value = confirming.copy(slideAgain = true)
    else launchSend(confirming, key)                  // the command service compares key.commandKey under its lock
}
```

- The slide's key holds the command, the `CommandKey` (vehicle and connection) and anything chosen in the sheet (the
  take off altitude): a change drops the slide, and what is sent is what the key confirmed.
- The sheet shows only on the connection it was opened on; once it cannot be shown it never reopens by itself.
- The sending and the result live outside the modal sheet, so RTL and land stay reachable meanwhile. A refusal never
  hides a command still waiting or an emergency; a result ends with its vehicle.
- A `KeyChanged` result is no failure: it asks for another slide, for the same vehicle only.
- Commands are offered only for a vehicle heard live: never in a replay or while the link is lost.

## One-off effects

Use a `Channel`-backed `Flow<Effect>` only for things that must happen exactly once and are not
state (e.g. a transient "command acknowledged" message). Collect it once in the screen with
`LaunchedEffect`. Command **failures** and alerts are state, not effects:
the operator must be able to see them until acknowledged.

## Screen

```kotlin
@Composable
fun HudScreen(
    modifier: Modifier = Modifier,
    viewModel: HudViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HudContent(state = state, onArmRequested = viewModel::onArmRequested, modifier = modifier)
}
```

`collectAsStateWithLifecycle` comes from `androidx.lifecycle:lifecycle-runtime-compose` and
`hiltViewModel` from `androidx.hilt:hilt-lifecycle-viewmodel-compose`; the `gcs.android.feature` convention plugin
adds them to every feature.

## Testing

Every ViewModel has a test with JUnit 5 + MockK + Kluent + Turbine and `MainDispatcherExtension`
from `:core:testing` (see `android-tests`).
