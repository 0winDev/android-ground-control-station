# 0001. Write our own MAVLink v2 codec instead of using a library

- Status: Proposed
- Date: 2026-10-08
- Issue: #1

## Context

GCS talks to ArduPilot over MAVLink v2 on UDP. Everything the operator sees and every command sent
goes through the codec (framing, checksum, parsing and, from v0.6, signing), so its behavior on bad
input is a safety concern: radio links deliver corrupted, truncated and unexpected bytes.

The project is also a portfolio piece for defense and aerospace roles. Being able to explain the
protocol at byte level — frame layout, CRC_EXTRA, field reordering, payload truncation, signing — is
a goal in itself.

Only a handful of `common` messages are needed per phase (HEARTBEAT in v0.1, a few telemetry messages
in v0.2, commands and mission messages later), not the full dialect.

Options on the JVM/Android listed by the [MAVLink guide](https://mavlink.io/en/#mavlink-project-generatorslanguages):

- [dronefleet/mavlink](https://github.com/dronefleet/mavlink): idiomatic Java library; last push to the
  repository in July 2024.
- [mavlink-kotlin](https://github.com/divyanshupundir/mavlink-kotlin): Kotlin code generator and
  runtime with coroutines support; active (latest release Nov 2025).
- Official `mavgen` Java output (`pymavlink`'s generator supports Java).
- [MAVSDK-Java](https://github.com/mavlink/MAVSDK-Java): high-level SDK backed by `mavsdk_server`
  (a separate native server process reached over gRPC), which hides the protocol behind an API.

## Decision

We write our own MAVLink v2 codec in pure Kotlin in `:core:mavlink`, by hand, covering only the
messages each phase needs. It has no dependencies at all (enforced by `verifyModuleGraph`). It is
developed test-first from real packets captured from ArduPilot SITL, and reviewed against the
official specification. Its contract is: invalid input is dropped and counted, never thrown.

Existing libraries remain useful as references to cross-check our decoding (never as dependencies).

## Alternatives considered

### mavlink-kotlin
- Pros: Kotlin, maintained, generated from the official XML, coroutines-friendly.
- Cons: hides exactly the part we must be able to explain; generated code for the whole dialect;
  third-party code in the safety-relevant path.
- Why not: defeats the learning and demonstration goal.

### dronefleet/mavlink
- Pros: idiomatic, widely used.
- Cons: no repository activity since July 2024; reflection-based Java API; same "black box" issue.
- Why not: maintenance risk plus the same goal mismatch.

### mavgen Java output
- Pros: official generator, always in sync with the XML.
- Cons: generated Java for the full dialect, not idiomatic Kotlin; still not hand-understood.
- Why not: same goal mismatch; we may still use the XML as the source of truth for our tests.

### MAVSDK-Java
- Pros: high-level API (missions, telemetry, actions) maintained by the MAVLink project.
- Cons: requires the `mavsdk_server` binary and gRPC; the protocol is hidden; less control over
  failure handling.
- Why not: the opposite of what the project needs to show.

## Consequences

- Positive: full understanding and control of parsing and error handling; zero dependencies in the
  most critical module; small, auditable code; a strong interview topic.
- Negative / costs we accept: more work and risk of protocol bugs, mitigated by tests from real
  SITL captures, fuzz-style tests, review against the spec (`mavlink-reviewer`) and comparison with
  QGroundControl.
- Follow-ups: every new message needs its CRC_EXTRA and field order verified against the official
  definitions; revisit if the scope grows beyond what is reasonable to hand-write.
