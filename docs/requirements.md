# Requirements

Every requirement has a stable ID (`REQ-xxx`, never reused). Tests that cover a requirement are tagged
with it (`@Tag("REQ-xxx")`), and the matrix below maps each requirement to its verification. This is a
practice **inspired by** DO-178C-style traceability, not a certification claim.

Rules:

- New requirements are added in the PR of the first issue that needs them, with the next free ID.
- A requirement is **Verified** only when its verification exists and passes on `main`. The PR that adds
  that verification marks it Verified, since both reach `main` together.
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
| REQ-020 | The first vehicle heard shall stay the active vehicle until the app restarts; HEARTBEATs from other system IDs shall be shown as a warning and never merged into the active vehicle. | T, S |
| REQ-021 | The GCS shall send its own HEARTBEAT (MAVLink 2, `MAV_TYPE_GCS`, system 255, component 190) at 1 Hz to the active vehicle while that vehicle's link is CONNECTED, and stop sending when the link is lost or closed. | T, S |

## Requirement → verification matrix

| ID | Verified by | Status |
|---|---|---|
| REQ-001 | `verifyModuleGraph` (`:core:mavlink` runtime classpath = Kotlin stdlib only); `core/mavlink/.../ModulePurityTest` | Verified |
| REQ-002 | `verifyModuleGraph` (allowlist in `build-logic/.../ModuleGraph.kt`); `core/domain/.../ModulePurityTest`; `core/transport/.../ModulePurityTest` | Verified |
| REQ-003 | `.github/workflows/android-ci.yml` + branch protection on `main` (required check "Build and verify") | Verified |
| REQ-004 | gitleaks hook in `.pre-commit-config.yaml`; `.gitignore` excludes keystores and `*.mavkey` | Verified (I) |
| REQ-005 | `core/transport/.../UdpTransportTest`; `data/vehicle/.../DefaultLinkRepositoryTest`; `feature/hud/.../HudViewModelTest`; SITL check (PR #18) | Verified |
| REQ-006 | Android Lint `MissingTranslation` (error by default) on `lintDebug`; `values/` + `values-es/` | Verified |
| REQ-007 | `core/designsystem/.../GcsSizesTest` | Verified |
| REQ-010 | `core/mavlink/.../MavlinkParserTest` (real SITL frames: several per datagram, split, byte by byte, whole session in random datagrams, false long starts, signed) | Verified |
| REQ-011 | `core/mavlink/.../MavlinkCrcTest` (checksum + CRC_EXTRA from real SITL frames, flipped bit, wrong CRC_EXTRA); `core/mavlink/.../MavlinkParserTest` (bad checksum and unknown message ID dropped and counted) | Verified |
| REQ-012 | `core/mavlink/.../MavlinkParserTest` (1 MB of fixed-seed random bytes, empty datagram, noise counted as skipped bytes, LEN 0 dropped and counted) | Verified |
| REQ-013 | `core/mavlink/.../MavlinkDecoderTest` (real truncated SYS_STATUS, POWER_STATUS, SERVO_OUTPUT_RAW zero-filled to the full length; extra extension byte ignored; whole session); `core/mavlink/.../MavlinkMessagesTest` (full lengths) | Verified |
| REQ-014 | `core/mavlink/.../MavlinkParserTest` (unsupported incompatibility flag dropped and counted; unknown compatibility flag ignored) | Verified |
| REQ-015 | `core/mavlink/.../MavlinkDecoderTest` (real SITL HEARTBEATs, armed and disarmed in three modes; largest `custom_mode`; whole session) | Verified |
| REQ-016 | `data/vehicle/.../DefaultVehicleRepositoryTest` (real SITL HEARTBEATs from two system IDs kept apart; ground-station HEARTBEAT ignored); `data/vehicle/.../HeartbeatMapperTest` (only the autopilot component describes the vehicle); `data/vehicle/.../LinkSessionTest` | Verified |
| REQ-017 | Data side: `core/domain/.../LinkLossRuleTest` (3 s default, exactly at the timeout, custom timeout; "configurable" is build-time configuration in v0.1, through `VehicleDataModule.provideLinkLossRule`); `data/vehicle/.../DefaultVehicleRepositoryTest` (real SITL HEARTBEATs in virtual time: LOST exactly at the timeout with the last state kept, no flapping before it, per system ID, ground-station HEARTBEATs do not keep a vehicle alive, another component of the vehicle does not keep it alive, custom timeout, HEARTBEAT 1 ms before and exactly at the timeout, transport failure or end of datagrams, session stopped and restarted with the vehicle still CONNECTED, watchdog failure fails safe); `data/vehicle/.../ElapsedRealtimeTimeSourceTest` (the clock counts through device sleep). Operator warning: `feature/hud/.../HudViewModelTest` (full-screen LinkLost with the last known state; the time since the last HEARTBEAT keeps counting; LinkLost from the rule even while the watchdog still says CONNECTED, so a timer delayed by device sleep never hides the loss; LinkLost on time with no new event, also for a timeout that is not whole seconds; a local link fault shown on LinkLost; never a remembered CONNECTED when the HUD is observed again after the link timed out); `feature/hud/.../ElapsedTimeTest`. SITL check against QGroundControl (#13: Mission Planner SITL, ArduCopter V4.7.2-beta, emulator): the simulator stopped, link lost shown full-screen about 3 s after stopping the simulator (no entry animation) | Verified |
| REQ-018 | Data side: `data/vehicle/.../DefaultVehicleRepositoryTest` (mode and armed state from real SITL HEARTBEATs); `data/vehicle/.../ArduCopterFlightModesTest`; `data/vehicle/.../HeartbeatMapperTest`. Display: `feature/hud/.../HudViewModelTest` (connection, mode, armed state and system ID of the active vehicle; mode without a documented name). Mode and armed state show no age of their own: they are only shown while the vehicle is younger than the link-loss timeout, and past it the HUD shows LinkLost. SITL check against QGroundControl (#13: Mission Planner SITL, ArduCopter V4.7.2-beta, emulator): Stabilize, AltHold, Loiter, Guided, armed and disarmed match | Verified |
| REQ-019 | Data side: `data/vehicle/.../DefaultVehicleRepositoryTest` (a lost vehicle recovers with its next HEARTBEAT, also after the session restarts). **Missing:** recovery after a transport failure while the link is observed (follow-up issue #25). Display: `feature/hud/.../HudViewModelTest` (LinkLost back to connected). SITL check against QGroundControl (#13: Mission Planner SITL, ArduCopter V4.7.2-beta, emulator): the simulator restarted, the app reconnected by itself | Partially verified (transport-failure recovery: #25) |
| REQ-020 | `data/vehicle/.../DefaultVehicleRepositoryTest` (real SITL HEARTBEATs: no active vehicle from ground-station HEARTBEATs only; the first vehicle heard is active; a lower system ID heard later and the active vehicle going LOST do not change it); `feature/hud/.../HudViewModelTest` (other system IDs heard right now shown apart, lost ones not; LinkLost of the active vehicle with another one heard; waiting while the active vehicle is not in the map yet). SITL check against QGroundControl (#13: Mission Planner SITL, ArduCopter V4.7.2-beta, emulator) with a second system ID replayed by `tools/sitl/send_second_vehicle.py`: warning shown, the active vehicle kept | Verified |
| REQ-021 | `core/mavlink/.../MavlinkEncoderTest` (frames byte-identical to real captures: Mission Planner's GCS HEARTBEAT and four ArduPilot SITL HEARTBEATs; pymavlink frames for the largest and an asymmetric `custom_mode`; trailing-zero truncation and a one-byte all-zero payload through `frame`; out-of-range header and fields, `mavlink_version` other than 3); `core/transport/.../UdpTransportTest` (sender address reported; replies leave from the listening port; nothing sent while the link is closed); `data/vehicle/.../GcsHeartbeatSenderTest` (1 Hz, seq 0, 1, 2…; the 45th frame is Mission Planner's real one byte for byte; seq wraps after 255 and only advances when sent; failures do not stop it; the CONNECTED predicate's age check is what stops it after sleep); `data/vehicle/.../DefaultVehicleRepositoryTest` (sent to the active vehicle's address once per second while CONNECTED; stops when it is LOST, also when the watchdog's timer runs late after sleep or the watchdog fails; never to another system ID; stops when the socket closes because nobody observes the link). Sender: logs a run of failures once, refuses to run twice. SITL check (#14: Mission Planner SITL, ArduCopter V4.7.2-beta, emulator API 36, `tools/sitl/sitl_relay.py` decoding the app's frames with pymavlink): fields and 1 Hz as specified, seq consecutive and wrapping 255 → 0; stops when only the downlink is cut and resumes with the next seq; stops in the background; a second system ID does not receive it or change the active vehicle | Verified |
