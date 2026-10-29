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
| REQ-011 | The codec shall validate each frame's checksum, including the message's CRC_EXTRA; frames with an invalid checksum shall be dropped and counted, and frames with an unknown message ID shall be counted and never decoded. | T |
| REQ-012 | The codec shall never throw on any input (corrupted, truncated or random bytes); invalid input shall be dropped and counted by reason. | T |
| REQ-013 | The codec shall accept MAVLink 2 payloads truncated of trailing zero bytes, restoring them before decoding. | T |
| REQ-014 | The codec shall drop frames whose incompatibility flags it does not support. | T |
| REQ-015 | The codec shall decode HEARTBEAT as defined in the MAVLink `common` dialect. | T |
| REQ-016 | Vehicle state shall be kept per MAVLink system ID (sysid). | T |
| REQ-017 | The link to a vehicle shall be declared lost when no HEARTBEAT has been received from it for a configurable timeout (default 3 s), and the operator shall be warned by an emergency that stays the dominant element on screen, above the vehicle's last known data and position, which shall be shown greyed with their age and never as current. | T, S |
| REQ-018 | The app shall show the connection state (connected/link lost), the flight mode and the armed state of the vehicle. | T, S |
| REQ-019 | The link shall recover automatically when HEARTBEATs resume, without restarting the app. | T, S |
| REQ-020 | The first vehicle heard shall stay the active vehicle until the app restarts; HEARTBEATs from other system IDs shall be shown as a warning and never merged into the active vehicle. | T, S |
| REQ-021 | The GCS shall send its own HEARTBEAT (MAVLink 2, `MAV_TYPE_GCS`, system 255, component 190) at 1 Hz to the active vehicle while that vehicle's link is CONNECTED, and stop sending when the link is lost or closed. | T, S |

## v0.2 — Telemetry

| ID | Requirement | Method |
|---|---|---|
| REQ-022 | The codec shall encode COMMAND_LONG and decode COMMAND_ACK as defined in the MAVLink `common` dialect. | T |
| REQ-023 | While the active vehicle is CONNECTED, the GCS shall request the telemetry messages it displays at defined rates, and request them again when they stop arriving. | T, S |
| REQ-024 | The codec shall decode GLOBAL_POSITION_INT, ATTITUDE and VFR_HUD as defined in the MAVLink `common` dialect. | T |
| REQ-025 | The codec shall decode SYS_STATUS and GPS_RAW_INT as defined in the MAVLink `common` dialect. | T |
| REQ-026 | Each telemetry value shall carry the time it was received, and be marked stale when older than its threshold. | T, S |
| REQ-027 | The GCS shall show the vehicle position in MGRS to 1 m, matching a reference converter; positions outside UTM coverage shall show as not available. | T |
| REQ-028 | The GCS shall raise prioritized alerts (advisory, caution, emergency), including low and critical battery at defined thresholds, ordered by priority. | T, S |
| REQ-029 | The HUD shall show position (degrees and MGRS), relative altitude, heading, ground and vertical speed, attitude, battery and GPS state of the active vehicle, each greyed and showing its age once it is stale, matching QGroundControl. | T, S |
| REQ-030 | The GCS shall offer a night theme and a high-contrast day theme, switchable by the operator. | T, S |
| REQ-031 | Telemetry values outside their documented range shall be treated as no value, never shown or used as current. | T |

## v0.3 — Map and recording

| ID | Requirement | Method |
|---|---|---|
| REQ-032 | The codec shall decode HOME_POSITION as defined in the MAVLink `common` dialect. | T |
| REQ-033 | The GCS shall keep the active vehicle's home position, requested from the vehicle and updated when it changes; until the vehicle reports one, home shall show as unknown. | T, S |
| REQ-034 | The map shall show the active vehicle's position and heading, its home and its trail, from tiles fetched over HTTPS without a paid key and with the map data attribution; a stale or unverified position shall be shown as such, and a map that fails to load shall never hide the vehicle, its heading, trail or home. | T, S |
| REQ-035 | The trail shall hold the active vehicle's verified positions up to a bounded length, broken wherever the position was not verified, stopped arriving or the vehicle rebooted, and start afresh with every link session, the only time the active vehicle changes (REQ-020). | T |
| REQ-036 | The GCS shall record every MAVLink frame received and sent on the link, unknown message IDs included, to a .tlog file in app-private storage (an 8-byte big-endian timestamp in microseconds since the Unix epoch, then the frame), readable by pymavlink, QGroundControl and Mission Planner; the recording shall stay open through link loss and close when the session stops or after 10 min without link. | T, S |
| REQ-037 | The GCS shall replay a recording through the same pipeline as the live link, at its recorded pace and on a replay clock that stops while paused, only after closing the live session, without transmitting or recording anything, and under a replay banner that stays visible over every alert, with alerts marked as recorded. | T, S |
| REQ-038 | The operator shall be able to export a recording, by sharing it or saving a copy on the device, and import a .tlog for replay; an imported file is untrusted input and shall never crash the app. | T, S |
| REQ-039 | The GCS shall keep its flight recordings within 256 MiB, deleting the oldest when a recording opens and when it closes, and never the one being recorded nor the last one closed, which it remembers across restarts. | T |

## v0.4 — Commands and pre-flight

| ID | Requirement | Method |
|---|---|---|
| REQ-040 | The codec shall decode STATUSTEXT, including its `id` and `chunk_seq` extension fields, as defined in the MAVLink `common` dialect. | T |
| REQ-041 | The codec shall encode PARAM_REQUEST_READ and decode PARAM_VALUE as defined in the MAVLink `common` dialect. | T |
| REQ-042 | The GCS shall send arm, RTL and land to the active vehicle's autopilot one at a time, each ending in exactly one result, logged once: the matching COMMAND_ACK's, or no answer after 1.5 s per attempt; RTL and land shall be sent up to three times with `confirmation` 0, 1, 2 and arm once; after IN_PROGRESS it shall wait 10 s more without re-sending; after no answer the same command shall be refused for a late window of 1.5 s, 10 s for arm and take off; a new RTL or land shall supersede a pending RTL or land, and arm shall never supersede nor be superseded (ADR 0008). | T, S |
| REQ-043 | No command shall be sent during a replay, to a system ID other than the active vehicle's, while the active vehicle is LOST, or while its HEARTBEATs come from an address other than its first one in the session; checked before every attempt under the sender's lock. | T, S |
| REQ-044 | Take off shall be sent only while the active vehicle is armed and on the ground by both its landed state (an ON_GROUND under 0.5 s old, REQ-052) and its position (under 0.5 s old, at most 1 m above home, at most 0.5 m/s vertically), checked before each step under the sender's lock; it shall put the vehicle in GUIDED first when needed and send NAV_TAKEOFF only after that mode change was accepted and a HEARTBEAT shows GUIDED, to an altitude the operator picks between 2 and 30 m (10 m by default), each step once; otherwise it shall stop with its reason. RTL or land shall supersede it at any step, and a new take off shall be refused for 10 s after one left without answer or was superseded once an attempt was about to leave (ADR 0008). | T, S |
| REQ-052 | The codec shall decode EXTENDED_SYS_STATE as defined in the MAVLink `common` dialect; the GCS shall request it from the active vehicle at 4 Hz and keep its landed state with its age, and only an ON_GROUND younger than 0.5 s shall count as on the ground, never an unknown, missing or older one. | T, S |

## Requirement → verification matrix

| ID | Verified by | Status |
|---|---|---|
| REQ-001 | `verifyModuleGraph` (`:core:mavlink` runtime classpath = Kotlin stdlib only); `ModulePurityTest` | Verified |
| REQ-002 | `verifyModuleGraph` (allowlist in `build-logic/.../ModuleGraph.kt`); `ModulePurityTest` of `:core:domain` and `:core:transport` | Verified |
| REQ-003 | `.github/workflows/android-ci.yml` + branch protection on `main` (required check "Build and verify") | Verified |
| REQ-004 | gitleaks hook in `.pre-commit-config.yaml`; `.gitignore` excludes keystores and `*.mavkey` | Verified (I) |
| REQ-005 | `UdpTransportTest`, `DefaultLinkRepositoryTest`, `HudViewModelTest`. **S:** PR #18 | Verified |
| REQ-006 | Android Lint `MissingTranslation` (error by default) on `lintDebug`; `values/` + `values-es/` | Verified |
| REQ-007 | `GcsSizesTest` | Verified |
| REQ-010 | `MavlinkParserTest`, `MavlinkParserRawTest` (real SITL frames) | Verified |
| REQ-011 | `MavlinkCrcTest`, `MavlinkParserTest`, `MavlinkParserRawTest` (real SITL frames) | Verified |
| REQ-012 | `MavlinkParserTest`, `MavlinkParserRawTest`, `TlogReaderTest` (1 MB of fixed-seed random bytes, damaged logs) | Verified |
| REQ-013 | `MavlinkDecoderTest` (real truncated frames), `MavlinkMessagesTest` | Verified |
| REQ-014 | `MavlinkParserTest` | Verified |
| REQ-015 | `MavlinkDecoderTest` (real SITL HEARTBEATs) | Verified |
| REQ-016 | `DefaultVehicleRepositoryTest`, `HeartbeatMapperTest`, `LinkSessionTest` | Verified |
| REQ-017 | `LinkLossRuleTest`, `DefaultVehicleRepositoryTest`, `ElapsedRealtimeTimeSourceTest`, `HudViewModelTest`, `ElapsedTimeTest`, `HudViewModelLastDataTest`, `MapViewModelTest`. **S:** PR #27; #73 (a downlink cut in flight: the banner over the map, the last data greyed, back by itself) | Verified |
| REQ-018 | `DefaultVehicleRepositoryTest`, `ArduCopterFlightModesTest`, `HeartbeatMapperTest`, `HudViewModelTest`. **S:** PR #27 | Verified |
| REQ-019 | `DefaultVehicleRepositoryTest`, `LinkSessionTest`, `DefaultLinkRepositoryTest`, `HudViewModelTest`. **S:** PR #27, PR #32 | Verified |
| REQ-020 | `DefaultVehicleRepositoryTest`, `HudViewModelTest`. **S:** PR #27 (`tools/sitl/send_second_vehicle.py`) | Verified |
| REQ-021 | `MavlinkEncoderTest`, `UdpTransportTest`, `GcsHeartbeatSenderTest`, `DefaultVehicleRepositoryTest`. **S:** PR #30 | Verified |
| REQ-022 | `MavlinkEncoderTest`, `MavlinkDecoderTest`, `MavlinkMessagesTest` (Mission Planner's and ArduCopter's real frames) | Verified |
| REQ-023 | `TelemetryRequesterTest`, `DefaultVehicleRepositoryTest`, `GcsMessageSenderTest`, `TelemetryFromLinkTest`. **S:** PR #46, PR #50 (about 932 B/s) | Verified |
| REQ-024 | `MavlinkDecoderTest`, `MavlinkMessagesTest` (against pymavlink and `mavlink/mavlink` master) | Verified |
| REQ-025 | `MavlinkDecoderTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (against pymavlink) | Verified |
| REQ-026 | `StalenessRuleTest`, `TelemetryTest`, `TelemetryMapperTest`, `TelemetryStoreTest`, `DefaultTelemetryRepositoryTest`, `TelemetryFromLinkTest`. **S:** PR #58 | Verified |
| REQ-027 | `MgrsTest`, `UtmTest` against `geo/mgrs-reference.csv` (GeographicLib GeoConvert 2.7, NGA GEOTRANS 2.4.2) | Verified |
| REQ-028 | `AlertRuleTest`, `DefaultAlertRepositoryTest`, `BootBatteryAlertTest`, `TelemetryMapperTest`, `AlertBannerTest`, `HudViewModelAlertTest`. **S:** PR #54, PR #59 | Verified |
| REQ-029 | `HudViewModelTest`, `HudViewModelLastDataTest`, `PositionTrustTest`, `PositionWarningTest`, `DisplayRoundingTest`, `BatteryChipStateTest`. **S:** PR #57, PR #58 | Verified |
| REQ-030 | `ContrastTest`, `ThemePreferencesTest`, `ThemeViewModelTest`. **S:** PR #60 | Verified |
| REQ-031 | `TelemetryMapperTest`, `TelemetryStoreTest`, `TelemetrySessionTest` (ranges from `common.xml`, `standard.xml` and WGS84) | Verified |
| REQ-032 | `MavlinkDecoderTest`, `MavlinkMessagesTest` (ArduCopter's real HOME_POSITION, against pymavlink and `mavlink/mavlink` master) | Verified |
| REQ-033 | `TelemetryMapperTest`, `TelemetryStoreTest`, `TelemetryRequesterTest`, `DefaultTelemetryRepositoryTest`, `DefaultVehicleRepositoryTest`. **S:** #69 (ArduCopter 4.7.2: home as QGroundControl shows it, forgotten on a reboot) | Verified |
| REQ-034 | `MapViewModelTest`, `MapStyleTest`, `MapOverlapTest`, `OffScreenTest`, `NightStyleTest`, `ContrastTest`, `AttributionTest`, `FollowDeadZoneTest`, `StableInsetTest`, `LicencesAvailableTest` (ADR 0005). **S:** #73 (ArduCopter 4.7.2, QGroundControl 5.0.8: a Guided flight to a Go-to point where QGroundControl's map shows it; a map without network checked on the emulator with an unreachable style host) | Verified |
| REQ-035 | `GreatCircleTest`, `TrailRuleTest`, `TelemetryStoreTrailTest`, `DefaultTelemetryRepositoryTrailTest`, `MapViewModelTest`. **S:** #73 (the trail as QGroundControl draws it) | Verified |
| REQ-036 | `MavlinkParserRawSessionTest`, `MavlinkParserRawTest`, `TlogReaderTest`, `TlogWriterTest`, `RecordingRuleTest`, `FlightRecorderTest`, `LinkSessionTest`, `GcsMessageSenderTest`, `BackupRulesTest` (ADR 0006). **S:** #70 (ArduCopter 4.7.2: every frame received and sent counted by pymavlink, equal to the relay's; opened in QGroundControl and Mission Planner); #74 (kept through a rotation) | Verified |
| REQ-037 | `ReplayClockTest`, `PipelineClockTest`, `TlogDatagramSourceTest`, `LinkSessionReplayTest`, `ReplayPipelineTest`, `SessionChangeTest`, `DefaultReplayRepositoryTest`, `ReplayRuleTest`, `ReplayStateTest`, `RecheckTest`, `HudViewModelReplayTest`, `HudViewModelAlertTest`, `LinkChipStateTest`, `ReplayViewModelTest`, `AlertBannerTest`, `FlightAttentionRuleTest`, `FlightGuardViewModelTest`, `DestinationTest`. **S:** #71 (refused while a live vehicle is connected or armed); #74's SITL session (replayed without the simulator under the REPLAY banner, with no socket bound, HUD values equal to pymavlink's) | Verified |
| REQ-038 | `ImportRuleTest`, `RecordingRuleTest`, `RecordingScanTest`, `RecordingsFolderTest`, `DefaultRecordingRepositoryTest`, `FlightRecorderTest`, `ClosingOnCancelTest`, `RecordingsViewModelTest`, `ContentImportSourceTest`, `ContentExportTargetTest`, `SizeTextTest`. **S:** #74 (a QGroundControl log imported and replayed; random bytes and a 257 MiB file refused without a crash; shared and saved byte for byte) | Verified |
| REQ-039 | `RecordingRuleTest`, `FlightRecorderTest`, `LastClosedMarkerTest` | Verified |
| REQ-040 | `MavlinkDecoderTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter's real STATUSTEXT and ArduCopter 4.7.2's arming texts, against pymavlink and `mavlink/mavlink` master) | Verified |
| REQ-041 | `MavlinkEncoderTest`, `ParamRequestReadTest`, `MavlinkDecoderParamTest`, `MavlinkDecoderTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter's real PARAM_VALUE and pymavlink's PARAM_REQUEST_READ) | Verified |
| REQ-042 | `DefaultCommandRepositoryTest`, `CommandMessagesTest` (`common.xml` on `mavlink/mavlink` master). **S:** #98 (ArduCopter 4.7.2: arm, RTL and land accepted as QGroundControl shows them; with the relay dropping commands, RTL sent with `confirmation` 0, 1, 2 1.5 s apart and arm once, each logged once as no answer) | Verified |
| REQ-043 | `CommandRouteTest`, `GcsMessageSenderTest`, `DefaultCommandRepositoryTest`. **S:** #98 (ArduCopter 4.7.2: not sent to system 2, while LOST, or after the relay restarted on another port; sent again once the link came back on its first route) | Verified |
| REQ-044 | `TakeOffAltitudeRuleTest`, `GroundPositionRuleTest`, `LandedRuleTest`, `DefaultCommandRepositoryTest` (`common.xml` on `mavlink/mavlink` master, ArduCopter's `mode.h` for GUIDED 4) | Pending SITL |
| REQ-052 | `MavlinkDecoderExtendedSysStateTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter 4.7.2's real frames against pymavlink and `mavlink/mavlink` master), `TelemetryMapperTest`, `LandedRuleTest`, `AlertRuleTest`, `TelemetryRequesterTest`, `StalenessRuleTest`. **S:** #112 (ArduCopter 4.7.2 at 1 Hz: requested by the app and accepted, 384 frames 0.96–1.04 s apart through three take offs and landings, 1, 3, 2, 4, 1 as QGroundControl's MAVLink Inspector showed; at 4 Hz on a freshly started SITL: none before the app's request, then 109 frames 0.24–0.28 s apart, within `LandedRule`'s 0.5 s) | Verified |

## Known limits

- REQ-019: a real socket failure could not be reproduced in SITL, so transport recovery rests on the tests.
- REQ-023: an ACK that arrives late is taken as the next request's answer.
- REQ-025: a `time_usec` of 2⁶³ or more reads as a negative `Long`.
- REQ-027: at exactly 84°N the references disagree and the GCS says not available (#40, see the CSV README).
- REQ-028: the voltage is not judged (#53).
- REQ-028: a change of active vehicle can briefly raise false alerts.
- REQ-028: the armed state is not authenticated until signing (`docs/security.md`, K-2).
- REQ-029: navigation without GPS shows as not verified, and a frozen position with a GPS fix is not caught.
- REQ-030: before Android 12 a day launch shows the night starting window for an instant.
- REQ-032: zero-fill restores a truncated `q` as 0, not NaN; the data layer takes both as no orientation (#69).
- REQ-034: MapLibre ignores the system proxy, so a dead network is checked with an unreachable style host.
- REQ-036: MAVLink 1 frames from other stations are not recorded (ADR 0006).
- REQ-036: a flight's recording can be replayed only after it closes, 10 min after the link is lost (#89).
- REQ-037: a Mission Planner log's MAVLink 1 frames are skipped (ADR 0006).
- REQ-037: a replay nobody observes for 5 s plays again from its start.
- REQ-037: the replay clock counts device sleep, so what a sleep skipped plays at once on waking.
- REQ-037: from one replay straight to the next, the HUD can show the first one's vehicles for a few ms.
- REQ-037: the GCS failsafe was not triggered in SITL: QGroundControl and Mission Planner kept their HEARTBEATs.
- REQ-038: delete of the recording in progress and import while recording were checked by hand only.
- REQ-039: an open recording grows past the limit, about 1.1 kB/s from ArduCopter SITL (256 MiB in some 65 h).
- REQ-040: a UTF-8 character split between two chunks decodes as U+FFFD in each; ArduPilot's texts are ASCII.
- REQ-040: the `PreArm:` and `Arm:` frames are derived from ArduCopter's code, not captured (a capture comes with #100 or #102).
- REQ-041: PARAM_REQUEST_READ is checked against pymavlink only; ArduCopter's answer to it is checked with SITL in #101.
- REQ-042: the ACK has no sequence number, so one later than its late window, or that of an RTL superseded by another RTL, answers the next same command.
- REQ-042: a command sent before the link is lost ends as no answer, never as not sent: the vehicle may have executed it.
- REQ-042: during the late window the same command is refused as busy while `pending` shows nothing.
- REQ-042: on a round trip over 11.5 s an earlier arm's late FAILED can answer a new arm while it arms the vehicle; the HEARTBEAT's armed state is the truth (#104).
- REQ-043: until signing (v0.6) whoever sends the first HEARTBEAT owns the route, and a forged ACK from it can fake an answer (`docs/security.md`, N-2).
- REQ-043: a change of the vehicle's address within a session (a router restart, a second route, a forger with its system ID) blocks every command until the session ends; #105 is mandatory before any real flight.
- REQ-043: another GCS behind the same forwarder uses this GCS's identity (255/190), so its ACKs pass as answers.
- REQ-044: a take off stopped after its mode change (accepted, or without answer) leaves the vehicle in GUIDED, armed on the ground, until `DISARM_DELAY` disarms it, never if it is 0.
- REQ-044: `DISARM_DELAY` counts from arming, so a take off started more than about 4.5 s after arming (10 s default) can find the vehicle disarmed and end as not armed.
- REQ-044: the position check needs home where the vehicle stands: ArduCopter sets it on arming; a home set elsewhere refuses the take off as not landed.
- REQ-044: a superseded take off keeps its window from the moment it is superseded, once its first step was about to leave, sent or not.
- REQ-044: the vehicle may still refuse a take off (FAILED) when it is not landed or already above the altitude; its reason text comes with #100.
- REQ-052: ArduCopter sends EXTENDED_SYS_STATE on a channel only once asked there, and its ON_GROUND comes from its land detector, after touchdown: until then nothing counts as on the ground.
- REQ-052: `vtol_state` is decoded but not used; ArduCopter always reports MC (3).
- REQ-052: unsigned until v0.6, so a forged ON_GROUND from the vehicle's route reads as on the ground (`docs/security.md`, N-2).
- REQ-052: a copter climbing by RC from the ground still reads as on the ground for up to 0.5 s after its last ON_GROUND.
- REQ-052: EXTENDED_SYS_STATE is the last stream of a request round, so after first contact or a recovery nothing counts as on the ground until the round reaches it (about 0.35 s, up to 7 s without answers).
- REQ-052: frames that waited in the socket buffer while the device slept look fresh on wake, so an old ON_GROUND can count for up to 0.5 s (N-2, limitation 3).
- REQ-052: the rule says nothing about the link, the route or the data source: a take off (#99) checks those under REQ-043.
