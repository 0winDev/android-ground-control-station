---
name: navigation-3
description: Navigation in GCS (Ground Control Station) — decision pending. GCS has no navigation yet (v0.0); this skill records the open decision and keeps Jetpack Navigation 3 reference docs for when the first multi-screen flow lands. Trigger on: "navigation", "NavHost", "NavDisplay", "add a screen/route", "back stack", "tabs", "navigate between screens".
---

# Navigation — GCS (decision pending)

## Status

GCS has no navigation code yet: `:app` shows a single screen. The UI prototype has top-level tabs
(Flight, Mission, Pre-flight, Logs, Settings) plus modal flows (takeoff confirmation, mission upload,
link lost).

## The decision to make

When the first issue needs more than one screen, decide the navigation library **in that issue** and
record it with the `adr` skill. Inputs for the decision:

- Jetpack Navigation 3 (`NavDisplay`, back stack as state) vs Navigation Compose 2.x.
- Check the current stable version on the official release notes at that moment — don't assume one.
- The "link lost" state is **not** a destination: it's an overlay driven by state that must appear
  above any screen.
- Navigation lives in `:app` (composition root); features expose screen composables and take
  navigation callbacks as lambda parameters, so features never depend on each other.

Until then, don't add a navigation dependency.

## Reference material (generic Navigation 3 docs)

- [Navigation 3 developer documentation](references/android/guide/navigation/navigation-3/index.md)
- [Navigation 2 → 3 migration guide](references/android/guide/navigation/navigation-3/migration-guide.md)
- [Type-safe destinations in Compose](references/android/guide/navigation/type-safe-destinations.md)
- Recipes: [basic](references/android/guide/navigation/navigation-3/recipes/basic.md),
  [saveable back stack](references/android/guide/navigation/navigation-3/recipes/basicsaveable.md),
  [entry provider DSL](references/android/guide/navigation/navigation-3/recipes/basicdsl.md),
  [common UI / multiple back stacks](references/android/guide/navigation/navigation-3/recipes/common-ui.md),
  [dialog](references/android/guide/navigation/navigation-3/recipes/dialog.md),
  [bottom sheet](references/android/guide/navigation/navigation-3/recipes/bottomsheet.md),
  [list-detail](references/android/guide/navigation/navigation-3/recipes/scenes-listdetail.md),
  [two-pane](references/android/guide/navigation/navigation-3/recipes/scenes-twopane.md),
  [material list-detail](references/android/guide/navigation/navigation-3/recipes/material-listdetail.md),
  [material supporting pane](references/android/guide/navigation/navigation-3/recipes/material-supportingpane.md),
  [animations](references/android/guide/navigation/navigation-3/recipes/animations.md),
  [multiple back stacks](references/android/guide/navigation/navigation-3/recipes/multiple-backstacks.md),
  [conditional](references/android/guide/navigation/navigation-3/recipes/conditional.md),
  [modular (Hilt)](references/android/guide/navigation/navigation-3/recipes/modular-hilt.md),
  [passing arguments](references/android/guide/navigation/navigation-3/recipes/passingarguments.md),
  [results as events](references/android/guide/navigation/navigation-3/recipes/results-event.md) /
  [as state](references/android/guide/navigation/navigation-3/recipes/results-state.md),
  [deep links basic](references/android/guide/navigation/navigation-3/recipes/deeplinks-basic.md) /
  [advanced](references/android/guide/navigation/navigation-3/recipes/deeplinks-advanced.md).
