# 0004. Add a pure Kotlin domain layer (`:core:domain`) between features and data

- Status: Proposed
- Date: 2026-10-08
- Issue: #1

## Context

The original architecture had four layers: features read state from `:data:vehicle` and ask it to
send commands and missions; `:data:vehicle` uses the codec (`:core:mavlink`) and the UDP transport
(`:core:transport`).

That leaves no obvious home for the rules that make the system safe when something goes wrong:

- mission validation before upload (maximum altitude, distance from home, geofence, waypoint count),
- pre-flight checks that block arming (GPS fix, battery, link, validated mission),
- geo math (distances, bearings, MGRS),
- data staleness (when a telemetry value is too old to trust) and link-loss thresholds.

`:data:vehicle` is an Android library (Hilt now, Room from v0.5). Putting these rules there mixes
them with protocol mapping and Android wiring, makes their tests Android unit tests, and lets
features depend on a module that, through it, can reach protocol types.

## Decision

We add `:core:domain`, a pure Kotlin (JVM) module with no dependencies, holding the domain models
(vehicle state per system ID, mission, waypoint, geo point), the repository **interfaces**, and the
safety-critical rules above.

The resulting dependency graph, enforced by `./gradlew verifyModuleGraph`:

```
:app ──► everything (Hilt wiring, navigation)
:feature:{hud,map,mission} ──► :core:domain, :core:designsystem
:data:vehicle ──► :core:domain, :core:mavlink, :core:transport
:core:domain, :core:mavlink, :core:transport, :core:designsystem ──► nothing
:core:testing ──► test-only helper (testImplementation)
```

- Features depend on `:core:domain` interfaces, never on `:data:vehicle`.
- `:data:vehicle` implements those interfaces (MAVLink ↔ domain mapping, link watchdog, command and
  mission protocol state machines) and `:app` binds them with Hilt.
- The mission upload state machine stays in `:data:vehicle` because it speaks the protocol; the
  validation it relies on lives in `:core:domain`.

## Alternatives considered

### Features → `:data:vehicle` → `:core:domain` (domain only for pure rules)
- Pros: matches the original "features read `:data:vehicle`" wording; fewer interfaces.
- Cons: features see the data module and, with `api` dependencies, possibly the codec; harder to test
  ViewModels against fakes.
- Why not: weaker isolation for little saving.

### No domain module (rules in `:data:vehicle`)
- Pros: one module less.
- Cons: safety rules mixed with Android/protocol code; slower tests; no clean place for coverage and
  fuzz-style tests of the rules.
- Why not: the rules are the part that most needs isolation and evidence.

## Consequences

- Positive: safety-critical logic is isolated, framework-free and fast to test (JUnit on the JVM,
  Kover coverage); features cannot reach the codec or the network even transitively; the split mirrors
  the partitioning of critical code in the sector (inspired by, not compliant with, DO-178C).
- Negative / costs we accept: one more module and repository interfaces that must be bound in
  `:app`; models are mapped at the `:data:vehicle` boundary.
- Follow-ups: add a coverage minimum for `:core:domain` together with the first real rules; keep the
  allowlist in `build-logic/.../ModuleGraph.kt` in sync with this ADR.
