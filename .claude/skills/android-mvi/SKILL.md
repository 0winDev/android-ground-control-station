---
name: android-mvi
description: State-driven presentation pattern for GCS (Ground Control Station) Compose screens. Use when creating a new screen or feature presentation layer, adding user interactions (including dangerous commands that need confirmation), or reviewing presentation code for UiState/ViewModel/Screen conventions.
---

# Presentation pattern — GCS

GCS is at v0.0 with no screens yet; this is the agreed target shape. Replace the illustrative names
with real references once the first screen lands.

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

Public ViewModel functions are the intents (`onArmRequested()`, `onArmConfirmed()`,
`onArmCancelled()`). No `onEvent(Event)` dispatcher unless a screen genuinely benefits from it.

### Dangerous commands: two-step confirmation in state

Arm, takeoff and changing the mission in flight never fire on the first tap:

```kotlin
// Illustrative
fun onArmRequested() {
    _state.update { it.copy(pendingConfirmation = PendingCommand.Arm) }
}

fun onArmConfirmed() {
    _state.update { it.copy(pendingConfirmation = null) }
    viewModelScope.launch { vehicleRepository.arm(systemId) }   // result comes back as state
}
```

The confirmation dialog renders from `pendingConfirmation`; cancelling clears it. Commands are
blocked entirely while the link is lost (enforced in the domain/data layer, mirrored in UI state).

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

`collectAsStateWithLifecycle` and `hiltViewModel` come in with the first screen (their dependencies
are added in that issue, not before).

## Testing

Every ViewModel has a test with JUnit 5 + MockK + Kluent + Turbine and `MainDispatcherExtension`
from `:core:testing` (see `android-tests`).
