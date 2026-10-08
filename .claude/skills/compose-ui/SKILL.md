---
name: compose-ui
description: |
  GCS (Ground Control Station) conventions for Compose: GcsTheme from :core:designsystem (night
  palette, Barlow / Barlow Condensed / IBM Plex Mono), large touch targets for field use, alert
  priorities, stale-data rendering, accessibility. Trigger on: Compose screen, GcsTheme,
  design system, colors, typography, touch target, @Preview, contentDescription, alert banner.
---

# Compose UI — GCS conventions

This skill covers only what is specific to GCS. For the ViewModel/UiState shape see `android-mvi`;
for general Compose practice, follow the official Jetpack Compose documentation.

## Core principle

The UI is dumb: it renders state and forwards intents. No protocol, validation or staleness logic in
composables.

## Design system (`:core:designsystem`, `com.owindev.gcs.core.designsystem`)

Derived from the UI prototype (tablet + phone, field use):

- **`GcsTheme { }`** wraps `MaterialTheme` with the GCS color scheme and typography. Every screen,
  preview and UI test is wrapped in it.
- **Colors**: dark "night" palette by default (near-black backgrounds, light grey text, cyan accent,
  semantic green/amber/red). Use the theme tokens; never hardcode `Color(0x…)` in a feature. A
  high-contrast day theme arrives with v0.2 (alerts + night mode).
- **Typography**:
  - Barlow Condensed — labels, headings, buttons (uppercase labels with letter spacing).
  - Barlow — body text.
  - IBM Plex Mono — numeric telemetry and coordinates (tabular digits, no jitter while values change).
  Fonts are bundled in `res/font` (OFL licensed, see the license files there): the app must work
  offline in the field.
- **Sizes**: large touch targets for field use (possibly with gloves). Use the design-system size
  tokens for buttons and tabs; never go below the minimum touch target token.

Check `core/designsystem/src/main/kotlin/...` for the real token names before using them.

## Safety-related UI rules

- **Alert priority** is visual and consistent: advisory < caution < emergency (aviation convention;
  `GcsTheme.colors.advisory/caution/emergency`), using the semantic
  colors and never color alone (icon + text too, for color-blind operators and sunlight).
- **Stale data**: each telemetry value shows its age and is greyed out once stale (flag provided by
  UiState).
- **Link lost**: full-screen, unmissable state; commands disabled; last known position frozen with
  its timestamp.
- **Dangerous commands** (arm, takeoff, mission change in flight) need an explicit confirmation step
  (see `android-mvi`); destructive buttons are visually distinct and never adjacent to their undo.

## Strings and accessibility

- All user-facing text in `values/strings.xml` (English) and `values-es/strings.xml` (Spanish);
  each module has its own `R`.
- `contentDescription` from string resources for meaningful icons; `null` for decorative ones.
- Units are part of the string (`%1$.1f m`), formatted with the device locale.

## Previews

Add a `@Preview` when it shows a meaningful state (e.g. stale value, link lost, emergency alert),
wrapped in `GcsTheme`. Don't add previews reflexively.
