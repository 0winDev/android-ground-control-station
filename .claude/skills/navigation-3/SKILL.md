---
name: navigation-3
description: Navigation in GCS (Ground Control Station) — decision pending. GCS has no navigation yet (v0.0); this skill records the open decision and points to the official Navigation 3 documentation for when the first multi-screen flow lands. Trigger on: "navigation", "NavHost", "NavDisplay", "add a screen/route", "back stack", "tabs", "navigate between screens".
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

## Reference material

Official documentation (check it at decision time; nothing is vendored in the repo):

- [Navigation 3 guide](https://developer.android.com/guide/navigation/navigation-3)
- [Navigation 2 → 3 migration guide](https://developer.android.com/guide/navigation/navigation-3/migration-guide)
- [Type safety in Navigation Compose](https://developer.android.com/guide/navigation/design/type-safety)
- [Navigation 3 recipes](https://github.com/android/nav3-recipes) (dialogs, bottom sheets, list-detail,
  multiple back stacks, modular navigation with Hilt, results, deep links)
