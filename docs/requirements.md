# Requirements

Every requirement has a stable ID (`REQ-xxx`, never reused). Tests that cover a requirement are tagged
with it (`@Tag("REQ-xxx")`), and the matrix below maps each requirement to its verification. This is a
practice **inspired by** DO-178C-style traceability, not a certification claim.

Rules:

- New requirements are added in the PR of the first issue that needs them, with the next free ID.
- A requirement is **Verified** only when its verification exists and passes on `main`.
- Changing a requirement's meaning means a new ID; the old one is marked `Superseded by REQ-yyy`.

Verification methods: **T** = automated test, **A** = automated check in the build/CI,
**I** = inspection/review, **S** = manual check against ArduPilot SITL compared with QGroundControl.

## v0.0 — Setup

| ID | Requirement | Method |
|---|---|---|
| REQ-001 | The MAVLink codec module (`:core:mavlink`) shall be pure Kotlin: no Android dependency and no third-party MAVLink library. | A, T |
| REQ-002 | Module dependencies shall flow in one direction (features → domain/design system; data → domain/codec/transport); `:core:domain` and `:core:transport` shall not depend on Android. The build shall fail when a rule is broken. | A, T |
| REQ-003 | Every change to `main` shall pass, in CI, the formatting check, static analysis, module rules, Android Lint, unit tests and the debug build. | A, I |
| REQ-004 | Secrets (including the MAVLink signing key) shall never be committed; every commit shall be scanned for secrets. | A, I |
| REQ-005 | The app shall receive UDP datagrams on an injectable port (default 14550) and show how many it has received. | T, S |
| REQ-006 | All user-facing text shall be available in English and Spanish. | A |
| REQ-007 | Interactive controls shall be large enough for field use: no touch target smaller than 48 dp. | T, I |

## v0.1 — Connection

| ID | Requirement | Method |
|---|---|---|
| REQ-010 | The codec shall extract MAVLink v2 frames from a byte stream (start marker, header, payload, checksum, optional signature), re-synchronising after invalid bytes, frames split across datagrams and several frames in one datagram. | T |
| REQ-011 | The codec shall validate each frame's checksum, including the message's CRC_EXTRA; frames with an invalid checksum or an unknown message ID shall be dropped and counted. | T |
| REQ-012 | The codec shall never throw on any input (corrupted, truncated or random bytes); invalid input shall be dropped and counted by reason. | T |
| REQ-013 | The codec shall accept MAVLink 2 payloads truncated of trailing zero bytes, restoring them before decoding. | T |
| REQ-014 | The codec shall drop frames whose incompatibility flags it does not support. | T |
| REQ-015 | The codec shall decode HEARTBEAT as defined in the MAVLink `common` dialect. | T |
| REQ-016 | Vehicle state shall be kept per MAVLink system ID (sysid). | T |
| REQ-017 | The link to a vehicle shall be declared lost when no HEARTBEAT has been received from it for a configurable timeout (default 3 s), and the operator shall be warned. | T, S |
| REQ-018 | The app shall show the connection state (connected/link lost), the flight mode and the armed state of the vehicle. | T, S |
| REQ-019 | The link shall recover automatically when HEARTBEATs resume, without restarting the app. | T, S |

## Requirement → verification matrix

| ID | Verified by | Status |
|---|---|---|
| REQ-001 | `verifyModuleGraph` (`:core:mavlink` runtime classpath = Kotlin stdlib only); `core/mavlink/.../ModulePurityTest` | Verified |
| REQ-002 | `verifyModuleGraph` (allowlist in `build-logic/.../ModuleGraph.kt`); `core/domain/.../ModulePurityTest`; `core/transport/.../ModulePurityTest` | Verified |
| REQ-003 | `.github/workflows/android-ci.yml` + branch protection on `main` (required check "Build and verify") | Verified |
| REQ-004 | gitleaks hook in `.pre-commit-config.yaml`; `.gitignore` excludes keystores and `*.mavkey` | Verified (I) |
| REQ-005 | `core/transport/.../UdpTransportTest`; `data/vehicle/.../DefaultLinkRepositoryTest`; `feature/hud/.../HudViewModelTest`; SITL check (PR of #6) | Pending (until merged to `main`) |
| REQ-006 | Android Lint `MissingTranslation` (error by default) on `lintDebug`; `values/` + `values-es/` | Verified |
| REQ-007 | `core/designsystem/.../GcsSizesTest` | Verified |
| REQ-010 | — | Pending (v0.1) |
| REQ-011 | `core/mavlink/.../MavlinkCrcTest` (checksum + CRC_EXTRA from real SITL frames, flipped bit, wrong CRC_EXTRA) | Partial (#8; drop-and-count in #9) |
| REQ-012 | — | Pending (v0.1) |
| REQ-013 | — | Pending (v0.1) |
| REQ-014 | — | Pending (v0.1) |
| REQ-015 | — | Pending (v0.1) |
| REQ-016 | — | Pending (v0.1) |
| REQ-017 | — | Pending (v0.1) |
| REQ-018 | — | Pending (v0.1) |
| REQ-019 | — | Pending (v0.1) |
