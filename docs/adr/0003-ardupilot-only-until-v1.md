# 0003. Support only ArduPilot until v1.0

- Status: Proposed
- Date: 2026-10-08
- Issue: #1

## Context

MAVLink is shared by several autopilots, mainly ArduPilot and PX4, but they differ in behavior that
a ground station depends on: how flight modes are encoded in HEARTBEAT's `custom_mode`, mission
handling details, parameter names, failsafe configuration and command support. Supporting both from
the start doubles the verification effort (two simulators, two sets of expected behaviors) for a
solo project.

ArduPilot provides an official simulator (SITL) that runs on the development machine, and
QGroundControl can connect to it as a reference.

## Decision

Until v1.0, GCS supports only **ArduPilot (ArduCopter)**. All development and verification is done
against ArduPilot SITL, compared with QGroundControl. Code that interprets autopilot-specific values
(e.g. flight mode names) stays isolated in `:data:vehicle`, so another autopilot can be added later
without touching the codec or the features.

## Alternatives considered

### ArduPilot and PX4 from the start
- Pros: wider applicability.
- Cons: two sets of mode encodings, mission and failsafe behaviors to implement and verify;
  slower progress on the safety features that matter for the project's goal.
- Why not: cost outweighs the benefit before v1.0.

### PX4 only
- Pros: also well supported (PX4 SITL, MAVSDK ecosystem).
- Cons: the project's reference environment and the original plan are built around ArduPilot SITL.
- Why not: no advantage for the goals of this project.

## Consequences

- Positive: one behavior to learn and verify; deeper, better-tested support for it.
- Negative / costs we accept: PX4 vehicles are not supported; some names and behaviors are
  ArduPilot-specific.
- Follow-ups: keep autopilot-specific mapping in one place; reconsider after v1.0 with a new ADR.
