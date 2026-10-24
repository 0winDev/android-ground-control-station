# 0007. Use Navigation 3, with the back stack owned by `:app`

- Status: Accepted
- Date: 2026-10-22
- Issue: #74

## Context

Until v0.3 the app is one screen: the flight screen (HUD over the map). #74 adds the second one, the recordings
list, from which a replay starts on the flight screen. The `navigation-3` skill left the library to the first issue
that needs two screens. The prototype has five top-level screens (Flight, Mission, Pre-flight, Logs, Settings), so
the choice must hold beyond two.

Constraints from the architecture and safety rules:

- Features never depend on each other: `:app` composes them and passes navigation as lambdas.
- LINK LOST and the alerts are state, not destinations: they must show over whatever is on screen.
- Leaving the flight screen in flight hides its alerts. The recordings list is reachable only while no live
  vehicle is armed, and the app goes back to the flight screen by itself when one arms or an emergency is raised.
  That return is a rule on state, so it must be testable without a UI.

What we know, checked on 2026-10-22:

- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) is stable: `1.2.0` on Google Maven
  (`androidx.navigation3:navigation3-runtime` and `navigation3-ui`, 23 September 2026), built for Compose. The app
  owns the back stack, a plain list: "you navigate between destinations by adding and removing items from a list".
  `1.2.0-alpha03` raised its compileSdk to 37, ours.
- `rememberNavBackStack` saves the stack only for `@Serializable` `NavKey`s
  ([Save state](https://developer.android.com/guide/navigation/navigation-3/save-state)), which needs
  kotlinx-serialization, not in the project. `NavDisplay` takes any list of keys.
- ViewModels are scoped to an entry by `rememberViewModelStoreNavEntryDecorator` from
  `androidx.lifecycle:lifecycle-viewmodel-navigation3`, released with lifecycle, `2.11.0` like ours.
- Navigation Compose 2.x (`androidx.navigation:navigation-compose`, `2.10.x` stable) keeps the back stack inside
  its `NavController`, with routes as type-safe serializable classes.

## Decision

We use Navigation 3 `1.2.0` in `:app` only. The destinations are a Kotlin `enum` (Flight, Recordings); the back
stack is a snapshot-state list of them that `:app` saves by name with `rememberSaveable`, so no serialization
plugin is added. `NavDisplay` shows the top of the stack, with the saveable-state and ViewModel-store entry
decorators so each screen keeps its own ViewModel.

Features expose screen composables and take lambdas (`onOpenRecordings`, `onReplayStart`); they never see the
back stack. The return to the flight screen is a pure function from the flight state (a live vehicle armed, an
emergency raised) to "leave Recordings", tested on its own and applied by `:app` to the stack. LINK LOST and the
alerts stay part of the flight screen's state, never a destination.

The guard that applies that return observes the flight only while the app is started (`repeatOnLifecycle`), as the
screens do: a backgrounded app lets the live link go after its stop timeout, so the GCS HEARTBEAT stops and the
vehicle's GCS failsafe follows the app, as before #74. Back in the foreground it compares the flight with the last
one it saw, so a vehicle that armed meanwhile still takes the operator back.

## Alternatives considered

### Navigation Compose 2.x
- Pros: mature, widely known, type-safe routes.
- Cons: the back stack lives inside `NavController`, so a rule that edits it from state goes through imperative
  calls; type-safe routes need kotlinx-serialization too.
- Why not: Navigation 3 makes the stack plain state, which is what the auto-return rule needs, and is the library
  built for Compose.

### No library: a `when` over a state variable
- Pros: no dependency, enough for two screens.
- Cons: back handling, saved state per screen and ViewModel scoping per screen written by hand, and rewritten
  when the next three screens arrive.
- Why not: the prototype already has five destinations.

### `rememberNavBackStack` with `@Serializable` keys
- Pros: the documented way; saves keys with arguments.
- Cons: adds the kotlinx-serialization plugin and library for two keys without arguments.
- Why not: an `enum` saved by name is enough until a destination needs arguments; then this is revisited.

## Consequences

- Positive:
  - The back stack is a list we can read, test and change from state.
  - Features stay independent; `:app` alone knows the destinations.
- Negative / costs we accept:
  - A young library: its API changed between 1.0 and 1.2 (scene strategies, back handling).
  - Our own saver for the stack instead of the documented one.
- Follow-ups:
  - The Settings destination (Licences moves there) and the other prototype screens are added as enum entries.
  - A destination with arguments (a mission, a recording) switches the stack to serializable keys.
