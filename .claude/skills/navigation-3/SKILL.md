---
name: navigation-3
description: Navigation in GCS (Ground Control Station) — decided in ADR 0007: Navigation 3 1.2.0 in `:app` only, destinations as an enum, a back stack saved by name, an automatic return to the flight screen. Use when adding a screen or destination, changing the back stack, or deciding when the operator may leave the flight screen. Trigger on: "navigation", "NavHost", "NavDisplay", "add a screen/route", "back stack", "tabs", "navigate between screens".
---

# Navigation — GCS (decided, ADR 0007)

## Status

Navigation 3 `1.2.0` (`androidx.navigation3:navigation3-runtime`, `navigation3-ui`) since #74, in `:app` only.
Destinations today: the flight screen (HUD over the map) and the recordings list. Read
[ADR 0007](../../../docs/adr/0007-navigation-3.md) before changing anything here.

## How it is built

- `app/.../navigation/Destination.kt`: the destinations are an `enum`. The flight screen is always at the bottom of
  the back stack. `BackStackSaver` saves the stack by name, so no serialization plugin is needed.
- `app/.../navigation/GcsNavigation.kt`: `NavDisplay` shows the top of a `mutableStateListOf` held by
  `rememberSaveable(saver = BackStackSaver)`, with `rememberSaveableStateHolderNavEntryDecorator()` and
  `rememberViewModelStoreNavEntryDecorator()`, so each screen keeps its own ViewModel.
- Features never see the back stack: they expose screen composables and take lambdas (`onOpenRecordings`,
  `onBack`, `onReplayStart`). Features never depend on each other.
- LINK LOST and the alerts are state on the flight screen, never a destination.

## Leaving the flight screen (safety)

- Another screen opens only while no live vehicle is armed, lost or not. A `null` callback hides its button, and
  `openRecordings(mayLeaveFlight)` checks the rule again when it runs, because a tap drawn a frame earlier can arrive
  after the vehicle armed.
- The app goes back to the flight screen by itself when a live vehicle arms or a new live emergency is raised.
  `FlightAttentionRule` (`:core:domain`) decides it, and `FlightGuardViewModel` applies it. A replay's vehicles and
  alerts never count.
- The guard is collected only while the app is started (`repeatOnLifecycle(STARTED)`), never with an
  activity-scoped `Eagerly`, which would keep the link alive in the background.

## Adding a destination

1. Add an entry to `Destination` and a branch in `GcsNavigation`'s `entryProvider`.
2. Pass navigation to the feature as lambdas. Gate every way out of the flight screen on `mayLeaveFlight`.
3. Test the rule without a UI: `FlightAttentionRuleTest`, `FlightGuardViewModelTest`, `DestinationTest`.
4. A destination with arguments (a mission, a recording) moves the stack to `@Serializable` `NavKey`s with
   `rememberNavBackStack`: record it in a new ADR that supersedes this part of 0007.

Planned next: Settings, with the licences moved there (#90, v1.0).

## Reference material

Official documentation (check the version against Google Maven before upgrading):

- [Navigation 3 guide](https://developer.android.com/guide/navigation/navigation-3)
- [Save state](https://developer.android.com/guide/navigation/navigation-3/save-state)
- [Navigation 3 recipes](https://github.com/android/nav3-recipes) (dialogs, bottom sheets, list-detail,
  multiple back stacks, modular navigation with Hilt, results, deep links)
