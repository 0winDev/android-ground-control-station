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
| REQ-036 | The GCS shall record every MAVLink frame received and sent on the link, unknown message IDs included, to a .tlog file in app-private storage (an 8-byte big-endian timestamp in microseconds since the Unix epoch, then the frame), readable by pymavlink, QGroundControl and Mission Planner; the recording shall stay open through link loss and close when the session stops or after 10 min without link. Superseded by REQ-049. | T, S |
| REQ-037 | The GCS shall replay a recording through the same pipeline as the live link, at its recorded pace and on a replay clock that stops while paused, only after closing the live session, without transmitting or recording anything, and under a replay banner that stays visible over every alert, with alerts marked as recorded. | T, S |
| REQ-038 | The operator shall be able to export a recording, by sharing it or saving a copy on the device, and import a .tlog for replay; an imported file is untrusted input and shall never crash the app. | T, S |
| REQ-039 | The GCS shall keep its flight recordings within 256 MiB, deleting the oldest when a recording opens and when it closes, and never the one being recorded nor the last one closed, which it remembers across restarts. | T |

## v0.4 — Commands and pre-flight

| ID | Requirement | Method |
|---|---|---|
| REQ-040 | The codec shall decode STATUSTEXT, including its `id` and `chunk_seq` extension fields, as defined in the MAVLink `common` dialect. | T |
| REQ-041 | The codec shall encode PARAM_REQUEST_READ and decode PARAM_VALUE as defined in the MAVLink `common` dialect. | T |
| REQ-042 | The GCS shall send arm, RTL and land to the active vehicle's autopilot one at a time, each ending in exactly one result, logged once: the matching COMMAND_ACK's, or no answer after 1.5 s per attempt; RTL and land shall be sent up to three times with `confirmation` 0, 1, 2 and arm once; after IN_PROGRESS it shall wait 10 s more without re-sending; after no answer the same command shall be refused for a late window of 1.5 s, 10 s for arm and take off; a new RTL or land shall supersede a pending RTL or land, and arm shall never supersede nor be superseded (ADR 0008). | T, S |
| REQ-043 | No command shall be sent during a replay, to a system ID other than the active vehicle's, while the active vehicle is LOST, or while its HEARTBEATs come from an address other than the one its commands take (its first one in the session, or the one the operator accepted, REQ-051); checked before every attempt under the sender's lock. | T, S |
| REQ-044 | Take off shall be sent only while the active vehicle is armed and on the ground by both its landed state (an ON_GROUND under 0.5 s old, REQ-052) and its position (under 0.5 s old, at most 1 m above home, at most 0.5 m/s vertically), checked before each step under the sender's lock; it shall put the vehicle in GUIDED first when needed and send NAV_TAKEOFF only after that mode change was accepted and a HEARTBEAT shows GUIDED, to an altitude the operator picks between 2 and 30 m (10 m by default), each step once; otherwise it shall stop with its reason. RTL or land shall supersede it at any step, and a new take off shall be refused for 10 s after one left without answer or was superseded once an attempt was about to leave (ADR 0008). | T, S |
| REQ-045 | The GCS shall warn, at caution level, that the vehicle may not be hearing it after 3 consecutive failed HEARTBEAT periods, until the next successful HEARTBEAT send, or after a command left without answer, showing its age, until a COMMAND_ACK from the active vehicle's pinned route and system ID (the next command's, a telemetry request's, or that command's own within its late window), the vehicle going LOST or the session ending. | T, S |
| REQ-046 | While a live vehicle is connected, the link, the GCS HEARTBEAT, the watchdog and the recording shall keep running with the app in the background, under a notification showing the link state; while any live vehicle is armed, nothing in the app shall stop them (ADR 0009). | T, S |
| REQ-047 | The GCS shall keep the active vehicle's autopilot STATUSTEXT messages of the link session, with their severity (unknown outside `MAV_SEVERITY` 0–7), time and origin, reassembled from chunks within 1 s (marked incomplete when a chunk is missing), newest first and at most 100, marked as recorded during a replay, and show them to the operator; it shall keep the latest `PreArm:` text apart with its time until the vehicle arms or reports its pre-arm checks healthy. That text is informational only: the vehicle's pre-arm state is SYS_STATUS's `MAV_SYS_STATUS_PREARM_CHECK`, which the pre-flight check reads (#102), since the text can be lost or grow old while the failure lasts. | T, S |
| REQ-048 | Before arming, the GCS shall evaluate defined pre-flight checks, each green, amber or red with its reason; a red check shall block arm and take off, an amber check shall be acknowledged before arming, and neither the checks nor the alerts shall ever block RTL or land. The checks are judged right before each send, from the active vehicle's data of the session; an acknowledgement counts only for the same item and reason, on the same connection and for 2 min; the uplink check needs a COMMAND_ACK since the vehicle became CONNECTED and none missing since, asked for again every 5 s until the uplink is confirmed. | T, S |
| REQ-049 | The GCS shall record every MAVLink frame received and sent on the link, unknown message IDs included, to a .tlog file in app-private storage (an 8-byte big-endian timestamp in microseconds since the Unix epoch, then the frame), readable by pymavlink, QGroundControl and Mission Planner; the recording shall stay open through link loss and close when the session stops, after 10 min without link, or once no live vehicle has been armed for 30 s after one was, by the HEARTBEATs received from each vehicle's autopilot; after that close the next frame shall open the next recording, and no frame shall be lost between one recording and the next. Supersedes REQ-036. | T, S |
| REQ-050 | Arm, take off, RTL and land shall be sent only after a slide-to-confirm gesture that names the action and the vehicle; the gesture shall be cancelled without sending if the active vehicle, the link, the replay state or the route changes during it, and the rules shall be checked again when it completes. | T, S |
| REQ-051 | While the active vehicle's HEARTBEATs come from an address other than the one its commands take, every command shall be held and the screen shall say why; once one other address has been heard alone for one link timeout, the GCS shall show both addresses and let the operator accept the new one with a slide-to-confirm gesture, which moves the command key, pins the new address and restarts the uplink evidence; addresses heard within one link timeout of each other shall never be offered, and the pinned address heard alone again shall reopen commands without acceptance. | T, S |
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
| REQ-028 | `AlertRuleTest`, `DefaultAlertRepositoryTest`, `BootBatteryAlertTest`, `TelemetryMapperTest`, `AlertBannerTest`, `HudViewModelAlertTest`, `BatteryThresholdsTest`, `BatteryMonitorReadTest`, `BatteryThresholdReadTest`, `FailsafeActionReadTest`, `ParameterStoreTest`, `DefaultBatteryThresholdRepositoryTest`, `BatteryParameterRequesterTest`, `ParametersFromLinkTest` (ArduCopter's real PARAM_VALUEs), `SentVoltageReadTest`, `BatteryAlertLimitsTest`, `BatteryVoltageAlertTest`. **S:** PR #54, PR #59, #101; #53 (ArduCopter 4.7.3, `BATT_MONITOR` 3 in flight: low and critical voltage raised 10.0 s after the first reading below 12.0 V and 11.5 V, within 0.7 s of the vehicle's own failsafe text; lowered in flight, both cleared 10.0 s later; `BATT_LOW_TIMER` 0 debounced 10 s; `BATT_OPTIONS` bit 6 said the voltage not judged; no "limits not read" on connections and reboots) | Verified |
| REQ-029 | `HudViewModelTest`, `HudViewModelLastDataTest`, `PositionTrustTest`, `PositionWarningTest`, `DisplayRoundingTest`, `BatteryChipStateTest`. **S:** PR #57, PR #58 | Verified |
| REQ-030 | `ContrastTest`, `ThemePreferencesTest`, `ThemeViewModelTest`. **S:** PR #60 | Verified |
| REQ-031 | `TelemetryMapperTest`, `TelemetryStoreTest`, `TelemetrySessionTest` (ranges from `common.xml`, `standard.xml` and WGS84) | Verified |
| REQ-032 | `MavlinkDecoderTest`, `MavlinkMessagesTest` (ArduCopter's real HOME_POSITION, against pymavlink and `mavlink/mavlink` master) | Verified |
| REQ-033 | `TelemetryMapperTest`, `TelemetryStoreTest`, `TelemetryRequesterTest`, `DefaultTelemetryRepositoryTest`, `DefaultVehicleRepositoryTest`. **S:** #69 (ArduCopter 4.7.2: home as QGroundControl shows it, forgotten on a reboot) | Verified |
| REQ-034 | `MapViewModelTest`, `MapStyleTest`, `MapOverlapTest`, `OffScreenTest`, `NightStyleTest`, `ContrastTest`, `AttributionTest`, `FollowDeadZoneTest`, `StableInsetTest`, `LicencesAvailableTest` (ADR 0005). **S:** #73 (ArduCopter 4.7.2, QGroundControl 5.0.8: a Guided flight to a Go-to point where QGroundControl's map shows it; a map without network checked on the emulator with an unreachable style host) | Verified |
| REQ-035 | `GreatCircleTest`, `TrailRuleTest`, `TelemetryStoreTrailTest`, `DefaultTelemetryRepositoryTrailTest`, `MapViewModelTest`. **S:** #73 (the trail as QGroundControl draws it) | Verified |
| REQ-036 | See REQ-049 | Superseded by REQ-049 |
| REQ-037 | `ReplayClockTest`, `PipelineClockTest`, `TlogDatagramSourceTest`, `LinkSessionReplayTest`, `ReplayPipelineTest`, `SessionChangeTest`, `DefaultReplayRepositoryTest`, `ReplayRuleTest`, `ReplayStateTest`, `RecheckTest`, `HudViewModelReplayTest`, `HudViewModelAlertTest`, `LinkChipStateTest`, `ReplayViewModelTest`, `AlertBannerTest`, `FlightAttentionRuleTest`, `FlightGuardViewModelTest`, `DestinationTest`. **S:** #71 (refused while a live vehicle is connected or armed); #74's SITL session (replayed without the simulator under the REPLAY banner, with no socket bound, HUD values equal to pymavlink's) | Verified |
| REQ-038 | `ImportRuleTest`, `RecordingRuleTest`, `RecordingScanTest`, `RecordingsFolderTest`, `DefaultRecordingRepositoryTest`, `FlightRecorderTest`, `ClosingOnCancelTest`, `RecordingsViewModelTest`, `ContentImportSourceTest`, `ContentExportTargetTest`, `SizeTextTest`. **S:** #74 (a QGroundControl log imported and replayed; random bytes and a 257 MiB file refused without a crash; shared and saved byte for byte) | Verified |
| REQ-039 | `RecordingRuleTest`, `FlightRecorderTest`, `LastClosedMarkerTest` | Verified |
| REQ-040 | `MavlinkDecoderTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter's real STATUSTEXT and ArduCopter 4.7.2's arming texts, against pymavlink and `mavlink/mavlink` master) | Verified |
| REQ-041 | `MavlinkEncoderTest`, `ParamRequestReadTest`, `MavlinkDecoderParamTest`, `MavlinkDecoderTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter's real PARAM_VALUE and pymavlink's PARAM_REQUEST_READ) | Verified |
| REQ-042 | `DefaultCommandRepositoryTest`, `CommandMessagesTest` (`common.xml` on `mavlink/mavlink` master). **S:** #98 (ArduCopter 4.7.2: arm, RTL and land accepted as QGroundControl shows them; with the relay dropping commands, RTL sent with `confirmation` 0, 1, 2 1.5 s apart and arm once, each logged once as no answer) | Verified |
| REQ-043 | `CommandRouteTest`, `GcsMessageSenderTest`, `DefaultCommandRepositoryTest`; the accepted route under REQ-051. **S:** #98 (ArduCopter 4.7.2: not sent to system 2, while LOST, or after the relay restarted on another port; sent again once the link came back on its first route) | Verified |
| REQ-044 | `TakeOffAltitudeRuleTest`, `GroundPositionRuleTest`, `LandedRuleTest`, `DefaultCommandRepositoryTest` (`common.xml` on `mavlink/mavlink` master, ArduCopter's `mode.h` for GUIDED 4). **S:** #99 (ArduCopter 4.7.2, `DISARM_DELAY` 10: from LOITER armed, DO_SET_MODE accepted in 23–32 ms with a GUIDED HEARTBEAT at once, NAV_TAKEOFF to 10 m, GUIDED and 10 m in QGroundControl; from GUIDED only NAV_TAKEOFF; in flight refused as not landed, nothing sent; with the relay dropping commands one DO_SET_MODE, no NAV_TAKEOFF, and the vehicle disarmed 10 s after arming) | Verified |
| REQ-045 | `UplinkRuleTest`, `GcsHeartbeatSenderTest`, `CommandUplinkTest`, `UplinkFromLinkTest`, `SessionChangeTest`, `HudViewModelUplinkTest`. **S:** #29 (ArduCopter 4.7.3 by AUTOPILOT_VERSION, relay dropping every frame from the app while telemetry flowed: HUD CONNECTED, RTL sent with `confirmation` 0, 1, 2 and ended without answer, then "a command got no answer" observed from 25 s to 46 s (first read late) while HEARTBEATs kept being sent, its age counted from the NoAnswer (a later run read 1 s within 2.8 s of it); with the uplink back a land answered in 18 ms cleared it; a downlink cut to LINK LOST cleared it and it stayed cleared once heard again; 360 × 640 dp in Spanish). `TelemetryRequesterTest` (a telemetry request's ACK ends a command without answer). **S:** #102 (the answer that confirms the uplink, see REQ-048) | Verified |
| REQ-046 | `BackgroundLinkRuleTest`, `BackgroundLinkWarningTest`, `LinkServiceControllerTest`, `BackgroundLinkViewModelTest`, `BackgroundLinkStateTest`, `BackgroundWarningsViewModelTest`, `LastExitNoteTest`. **S:** #28 (ArduCopter 4.7.3 by AUTOPILOT_VERSION, `FS_GCS_ENABLE` 1. Emulator API 36: `connectedDevice` (`types=0x10`), no SecurityException; armed in flight, 35 s in the background: 35 HEARTBEATs 1.001–1.005 s apart; recents refused while armed; Disconnect stops it disarmed and releases the wake lock; Disconnect with the app visible, then arming, starts it again. OPPO CPH2197, ColorOS 13, Android 13, unplugged, flying armed with the screen off 10 min and `force-idle` after 3: with ColorOS defaults `com.oplus.athena` killed the app after 5 min; with background activity allowed for the app, 594 HEARTBEATs in 600 s, at most 1.204 s apart, no LOST. Second PR, emulator API 36: notifications asked disarmed and posted at once on Allow; denied, the service runs under an advisory; never asked over an armed vehicle, asked once it disarmed; stock defaults only the battery advisory; restricted, a caution, the service losing the foreground once hidden and regaining it at the app's next start; unrestricted, none; force stop logged at the next start. OPPO: background activity allowed, no warning; ColorOS defaults, the advisory) | Verified |
| REQ-047 | `PreArmRuleTest`, `StatusTextAssemblerTest`, `MessageStoreTest`, `DefaultMessageRepositoryTest` (ArduCopter 4.7.3's real pre-arm texts and SYS_STATUS against pymavlink). **S:** #100 (ArduCopter 4.7.3 by AUTOPILOT_VERSION: with `SIM_GPS1_ENABLE` 0 in LOITER `PreArm: GPS 1: Bad fix` at severity 2, `id` 0, again 31 s later, the pre-arm bit enabled and not healthy 0.47 s after the first text; with two `SERIALn_PROTOCOL` 23 `PreArm: Multiple SERIAL ports configured for RC in`, cut at 50 bytes, `id` 0, received by the app through the relay; in STABILIZE with no GPS the bit healthy and no text; QGroundControl 5.0.8 showed the same texts in Vehicle Messages, `PreArm: GPS 1: Bad fix` in white and `EKF variance` in yellow, both at severity 2). Shown to the operator in #107 | Pending UI (#107) |
| REQ-048 | `PreflightRuleTest`, `PreflightAcknowledgementsTest`, `UplinkRuleTest`, `TelemetryMapperTest` (ArduCopter 4.7.3's real SYS_STATUS with the bit enabled, failing and with every check skipped, against pymavlink), `TelemetryRequesterTest`, `GcsHeartbeatSenderTest`, `UplinkFromLinkTest`, `SessionChangeTest`, `ActiveLinkChangesTest`, `PreflightGateTest`, `DefaultCommandRepositoryTest`. **S:** #102 (ArduCopter 4.7.3 official, git `d9c5097c`, emulator API 36: with SITL's defaults only the battery limits amber (warn only, no critical threshold), an arm refused until acknowledged and accepted once it was; with `SIM_GPS1_ENABLE` 0 in LOITER refused red on `PreArm: GPS 1: Bad fix`, current, and no 3D fix, no COMMAND_LONG 400 from the app in the relay's .tlog; with `ARMING_SKIPCHK` -1 the bit present, not enabled, amber; the GPS lost in flight, RTL sent (refused FAILED by the vehicle, which had already switched to LAND) and land accepted; at 10.8 V under a `BATT_CRT_VOLT` of 11 in flight, an arm refused red on the critical battery and RTL and land accepted; the round's ACKs dropped across a LOST, the uplink red, and confirmed 1.1 s after the relay forwarded again, by the SYS_STATUS request; one arm dropped, the next SYS_STATUS request answered 2 s after the relay forwarded again and the next arm accepted; QGroundControl 5.0.8 showed the same `PreArm: GPS 1: Bad fix` in Vehicle Messages). Acknowledged on the checklist in #106 | Pending UI (#106) |
| REQ-049 | `MavlinkParserRawSessionTest`, `MavlinkParserRawTest`, `TlogReaderTest`, `TlogWriterTest`, `RecordingRuleTest`, `FlightRecorderTest` (the disarm close on derived HEARTBEATs of system 2 and a gimbal, against pymavlink), `LinkSessionTest`, `GcsMessageSenderTest`, `BackupRulesTest`, `DefaultRecordingRepositoryTest` (ADR 0006). **S:** #70 (ArduCopter 4.7.2: every frame received and sent counted by pymavlink, equal to the relay's; opened in QGroundControl and Mission Planner); #74 (kept through a rotation); #89 (ArduCopter 4.7.3 official, git `d9c5097c`: disarmed by itself 10.0 s after arming on the ground without a take off and 2.8 s after touchdown in LAND; after a take off and a landing in LAND, the log closed 30 s after the first disarmed HEARTBEAT and the next opened with the next frame, 93 ms later; both logs, 3419 and 1325 frames, one unbroken run of the relay's (4557 forwarded, 187 sent) by pymavlink; with the link then lost, the closed log played at once from the list, without another replay first; QGroundControl 5.0.8 played the closed log) | Verified |
| REQ-050 | `SlideToConfirmStateTest` (the control: release at the end, early, cancelled, key, action or system ID changed, disabled, off the thumb, below the minimum travel, a second pointer, accessibility action), `DefaultCommandRepositoryTest`, `CommandKeyTest` (the service: a key no longer current or none sends nothing, a change between RTL attempts or during the take off's wait for GUIDED stops it, a closed route keeps its own reason; the key moves before each LOST and CONNECTED shows, on a reboot, after a replay and on a LOST only the HEARTBEAT gap proves, is none in a replay, and is checked before the pre-flight veto), `HudCommandsViewModelTest` (the flight screen: what is offered and why not, a sheet only on its connection that never reopens by itself, the slide's key with its command and altitude, every result in words, a refusal never hiding a command still waiting, another slide only for the same vehicle), `HudRouteViewModelTest` (the route's slide: its offer, closed by a key change and never reopened, another slide after an outdated answer). **S:** #103 (emulator API 36 at 360 × 640 dp, raw touch events: a full slide confirmed once with its key; short, off the thumb, cancelled by HOME, by a rotation, by a key change or a disable while held at the end, and a 148 dp track did not; a second finger carried a slide on to the end and confirmed; with TalkBack the control was focused with its label and a double tap or a double tap and hold did not confirm; night and day, English and Spanish); #104 (ArduCopter 4.8.0-dev: arm, take off to 10 m, RTL and land each sent only after its slide and accepted; an arm sheet open when the link was lost closed with the bar and did not reopen; QGroundControl 5.0.8 showed Guided at 10.0 m, then RTL, then Land, as the app did; 360 × 640 dp in Spanish; with TalkBack 16 the RTL slide, focused by touch, announced "Slide to RTL · SYS 1"; a TalkBack double tap and the custom action not verified: injected double taps never activate any control, Cancel included) | Verified except TalkBack |
| REQ-051 | `RouteRuleTest`, `ActiveRouteTest`, `CommandRouteTest`, `RouteAcceptanceTest`, `DefaultCommandRepositoryTest`, `TelemetryRequesterTest`, `HudCommandsRouteHoldTest`, `HudRouteViewModelTest`. **S:** pending (#105) | Pending SITL (#105) |
| REQ-052 | `MavlinkDecoderExtendedSysStateTest`, `MavlinkMessagesTest`, `MavlinkCrcTest` (ArduCopter 4.7.2's real frames against pymavlink and `mavlink/mavlink` master), `TelemetryMapperTest`, `LandedRuleTest`, `AlertRuleTest`, `TelemetryRequesterTest`, `StalenessRuleTest`. **S:** #112 (ArduCopter 4.7.2 at 1 Hz: requested by the app and accepted, 384 frames 0.96–1.04 s apart through three take offs and landings, 1, 3, 2, 4, 1 as QGroundControl's MAVLink Inspector showed; at 4 Hz on a freshly started SITL: none before the app's request, then 109 frames 0.24–0.28 s apart, within `LandedRule`'s 0.5 s) | Verified |

## Known limits

- REQ-019: a real socket failure could not be reproduced in SITL, so transport recovery rests on the tests.
- REQ-023: an ACK that arrives late is taken as the next request's answer.
- REQ-025: a `time_usec` of 2⁶³ or more reads as a negative `Long`.
- REQ-027: at exactly 84°N the references disagree and the GCS says not available (#40, see the CSV README).
- REQ-028: a voltage debounce counts from the readings received, so an alert can follow the vehicle's failsafe by up to one SYS_STATUS period (1 s; 0.52 and 0.64 s measured), and can come before it, or without it, on a voltage that crosses back and forth faster than the stream.
- REQ-028: with `BATT_LOW_TIMER` 0 the voltage still warns, though the vehicle never acts on it; with `BATT_FS_VOLTSRC` 1 and the raw voltage sent, it may warn of a sag the vehicle does not count.
- REQ-028: the vehicle's percentage is truncated, so an energy alert at its threshold can come up to one point early.
- REQ-028: an energy alert held while the vehicle's limits are not read again and then raised to critical at the GCS's no longer holds the vehicle's low limit if the energy rises.
- REQ-028: a change of active vehicle can briefly raise false alerts.
- REQ-028: the armed state is not authenticated until signing (`docs/security.md`, K-2).
- REQ-028: the battery parameters are read on each connection and reboot; a change from another GCS comes only in ArduPilot's PARAM_VALUE broadcast, and if that is lost the old value stays until the next round.
- REQ-028: `BATT_MONITOR` acts only after the vehicle reboots: set to 0 from another GCS, the GCS shows no monitor while the vehicle still acts until then.
- REQ-028: the battery settings are kept as last read while the vehicle is LOST; the battery is stale by then (3 s, as the LOST timeout) and stale readings judge nothing, and #102 must combine them with the link state.
- REQ-028: a capacity threshold on a monitor type outside 4, 7, 8, 21 and 31 reads as unknown, though some others measure current too.
- REQ-028: a failsafe level that is unknown never passes a gate or reads as protected (#53, #102).
- REQ-028: an analog current pin that reads nothing still counts as measuring current; parameters cannot show it.
- REQ-028: ArduPilot's Unhealthy battery failsafe (no healthy reading for 5 s) counts as action 0, so it can also skip a later action 6 or 7; it is not modelled.
- REQ-029: navigation without GPS shows as not verified, and a frozen position with a GPS fix is not caught.
- REQ-030: before Android 12 a day launch shows the night starting window for an instant.
- REQ-032: zero-fill restores a truncated `q` as 0, not NaN; the data layer takes both as no orientation (#69).
- REQ-034: MapLibre ignores the system proxy, so a dead network is checked with an unreachable style host.
- REQ-049: MAVLink 1 frames from other stations are not recorded (ADR 0006).
- REQ-049: a flight's recording can be replayed only after it closes: 30 s after the vehicles disarm, or 10 min after the link is lost while one is still armed; and a replay needs no live vehicle connected (REQ-037).
- REQ-049: a disarm on the ground followed by an arm after 30 s splits the session into two logs, as does ArduCopter's own disarm 10 s after an arm without a take off.
- REQ-049: a vehicle last heard armed and then lost keeps the log open until the 10-min close of the active vehicle.
- REQ-049: unsigned until v0.6, so a forged disarmed HEARTBEAT from a vehicle's system ID and component 1 can split a log early, and a forged armed one can keep it open; no frame is ever lost by either.
- REQ-050: a second finger can carry a slide on after the first lifts; it still has to reach the end of the track.
- REQ-050: a change of key reaches the control one recomposition late; the command service compares the key the slide started on (the active vehicle and its connection) with the current one under the sender's lock (#104).
- REQ-050: a route change within one connection is refused as `RouteChanged`, not as a key change; accepting the new route moves the key (REQ-051).
- REQ-050: a track allowing less than 160 dp of travel never confirms; only the accessibility action does.
- REQ-051: accepting is not authentication: until signing (v0.6) a forger with the active system ID, heard alone for 3 s and accepted by mistake, gets the commands (`docs/security.md`, N-2).
- REQ-051: a host sending the active system ID's HEARTBEAT at least once every 3 s, even under 1 Hz, keeps the route alternating and holds every command: RTL and land then come only from the transmitter or the vehicle's own failsafe.
- REQ-051: a legitimate change holds the commands for at least one link timeout (3 s) before the review is offered.
- REQ-051: accepting ends the pre-flight acknowledgements, which belong to a connection, and the uplink item stays red until a COMMAND_ACK comes by the new route.
- REQ-051: the flight screen also holds every command while no route is known on the current key, as for a moment after the key moves; the service judges the route again before each send.
- REQ-051: the addresses are shown to the operator and never logged.
- REQ-037: a Mission Planner log's MAVLink 1 frames are skipped (ADR 0006).
- REQ-037: a replay nobody observes for 5 s plays again from its start.
- REQ-037: the replay clock counts device sleep, so what a sleep skipped plays at once on waking.
- REQ-037: from one replay straight to the next, the HUD can show the first one's vehicles for a few ms.
- REQ-037: the GCS failsafe was not triggered in SITL: QGroundControl and Mission Planner kept their HEARTBEATs.
- REQ-038: delete of the recording in progress and import while recording were checked by hand only.
- REQ-039: an open recording grows past the limit, about 1.1 kB/s from ArduCopter SITL (256 MiB in some 65 h).
- REQ-040: a UTF-8 character split between two chunks decodes as U+FFFD in each; ArduPilot's texts are ASCII.
- REQ-040: the `PreArm:` and `Arm:` frames are derived from ArduCopter's code, not captured (a capture comes with #100 or #102).
- REQ-047: chunks are joined as decoded text, so a UTF-8 character split between them stays U+FFFD (REQ-040).
- REQ-047: ArduPilot cuts a `PreArm:` text at 50 bytes before its arguments, so a fixed one is never chunked; no chunked text was captured.
- REQ-047: a chunked text waiting for its last chunk is closed by the vehicle's next message, so through a LOST it waits until the link is back or the session ends.
- REQ-047: the first messages of a new session that arrive before the watchdog moves on are dropped, as the telemetry's are.
- REQ-047: ArduPilot queues a `PreArm:` text before it updates the pre-arm bit, so a healthy SYS_STATUS sent in between clears the reason until the next one, 30 s later.
- REQ-047: the bit can be unhealthy with no text for a while (after boot, or within 30 s of a new failure): "reason not received yet".
- REQ-047: unsigned until v0.6, so anyone on the vehicle's system ID and component 1 can add texts, push the 100 real ones out or plant a `PreArm:` (`docs/security.md`, A-1).
- REQ-041: PARAM_REQUEST_READ is checked against pymavlink and ArduCopter 4.7.3 SITL (#101) only, not a real autopilot.
- REQ-042: the ACK has no sequence number, so one later than its late window, or that of an RTL superseded by another RTL, answers the next same command.
- REQ-042: a command sent before the link is lost ends as no answer, never as not sent: the vehicle may have executed it.
- REQ-042: during the late window the same command is refused as busy while `pending` shows nothing.
- REQ-042: on a round trip over 11.5 s an earlier arm's late FAILED can answer a new arm while it arms the vehicle; the HEARTBEAT's armed state is the truth (#104).
- REQ-043: until signing (v0.6) whoever sends the first HEARTBEAT owns the route, and a forged ACK from it can fake an answer (`docs/security.md`, N-2).
- REQ-043: a change of the vehicle's address within a session (a router restart, a second route, a forger with its system ID) holds every command until the operator accepts the new address or the first one is heard alone again (REQ-051).
- REQ-043: another GCS behind the same forwarder uses this GCS's identity (255/190), so its ACKs pass as answers.
- REQ-044: a take off stopped after its mode change (accepted, or without answer) leaves the vehicle in GUIDED, armed on the ground, until `DISARM_DELAY` disarms it, never if it is 0; after an accepted change it ends as `TakeOffStopped`, which says so (#104).
- REQ-044: `DISARM_DELAY` counts from arming, so a take off started more than about 4.5 s after arming (10 s default) can find the vehicle disarmed and end as not armed.
- REQ-044: the position check needs home where the vehicle stands: ArduCopter sets it on arming; a home set elsewhere refuses the take off as not landed.
- REQ-044: a superseded take off keeps its window from the moment it is superseded, once its first step was about to leave, sent or not.
- REQ-044: the vehicle may still refuse a take off (FAILED) when it is not landed or already above the altitude; its reason text comes with #100.
- REQ-046: an OEM battery manager can kill the app despite its foreground service and wake lock (ColorOS did after 5 min with its defaults): the operator must allow the app's background activity.
- REQ-046: the Task Manager's Stop, force stop and a process kill end it with no callback; the vehicle's `FS_GCS` is then the fallback.
- REQ-046: a vehicle lost while armed holds the service and the wake lock until one of those.
- REQ-046: no background warning means only no known restriction: another manufacturer's battery manager may be invisible to Android's APIs (only ColorOS was measured).
- REQ-046: a restricted app's service loses the foreground once the app is hidden and regains it only at the app's next start.
- REQ-046: `POST_NOTIFICATIONS` is asked once per process and never over an armed vehicle; the app's settings page reaches battery optimisation on API 28+ only as checked (API 36, ColorOS 13).
- REQ-045: UDP gives no delivery proof: a send that leaves the device counts as sent, so an uplink cut beyond the device shows only through a command without answer.
- REQ-045: a vehicle that ignores a command reads the same as one that never heard it, hence "may not be hearing".
- REQ-045: the command case holds until an ACK, LOST or the session ends, however old, so it shows its age.
- REQ-045: no warning means nothing observed against the uplink, never that the vehicle hears this GCS; it is the state at first contact and after LOST.
- REQ-045: a cut beyond the device with no command sent shows nothing until the vehicle's own `FS_GCS` acts.
- REQ-045: a device that fails to send could not be reproduced in SITL: through the relay local sends always succeed, so the failed-periods case rests on the tests.
- REQ-048: the vehicle's pre-arm bit is healthy while armed, so in flight it says nothing; it gates arming only.
- REQ-048: unsigned until v0.6, so a forged SYS_STATUS from the vehicle's system ID can turn the pre-arm and battery checks green and a forged COMMAND_ACK from the pinned route can confirm the uplink; ArduPilot still refuses to arm on its own checks (`docs/security.md`, A-1).
- REQ-048: a COMMAND_ACK since CONNECTED says the vehicle heard this GCS then, not now: an uplink that fails later with no command sent stays green until a command goes unanswered.
- REQ-048: the battery is judged on its latest reading without the alerts' debounce, so a voltage sag of one reading below a threshold blocks arming and take off.
- REQ-048: a check that turns red after a take off's mode change was accepted stops it before NAV_TAKEOFF, leaving the vehicle armed in GUIDED on the ground until `DISARM_DELAY` (as REQ-044's); the result is `TakeOffStopped` (#104).
- REQ-048: while the uplink awaits an answer, SYS_STATUS is requested again every 5 s, so a command without answer clears REQ-045's warning within about 5 s once the vehicle answers that request.
- REQ-048: an alert evaluation adds its held emergency alerts only while someone observes the alerts; every emergency alert's cause is also judged on its own (tested).
- REQ-048: a battery failsafe that triggered stays latched until the vehicle reboots, so the battery and the vehicle's pre-arm checks stay red after the voltage recovers.
- REQ-048: an acknowledgement ends on a reconnection, a reboot or a new session even if the item is unchanged, and after 2 min.
- REQ-052: ArduCopter sends EXTENDED_SYS_STATE on a channel only once asked there, and its ON_GROUND comes from its land detector, after touchdown: until then nothing counts as on the ground.
- REQ-052: `vtol_state` is decoded but not used; ArduCopter always reports MC (3).
- REQ-052: unsigned until v0.6, so a forged ON_GROUND from the vehicle's route reads as on the ground (`docs/security.md`, N-2).
- REQ-052: a copter climbing by RC from the ground still reads as on the ground for up to 0.5 s after its last ON_GROUND.
- REQ-052: EXTENDED_SYS_STATE is the last stream of a request round, so after first contact or a recovery nothing counts as on the ground until the round reaches it (about 0.35 s, up to 7 s without answers).
- REQ-052: frames that waited in the socket buffer while the device slept look fresh on wake, so an old ON_GROUND can count for up to 0.5 s (N-2, limitation 3).
- REQ-052: the rule says nothing about the link, the route or the data source: a take off (#99) checks those under REQ-043.
