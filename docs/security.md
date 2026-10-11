# Security checklist (OWASP MASVS)

A checklist **inspired by** the [OWASP Mobile Application Security Verification Standard
(MASVS)](https://mas.owasp.org/MASVS/) v2, adapted to GCS. It is not an audit or a certification.
It is filled in as phases land (most items belong to v0.6 Link security and v1.0); the
`safety-reviewer` agent updates it when a PR changes an item's status.

Status: ✅ done · 🚧 in progress · ⏳ pending · ➖ not applicable (with reason)

## Threat model (summary)

- **Assets:** the vehicle's control link (commands, missions), the MAVLink signing key, mission data,
  flight logs (.tlog).
- **Main threats:** spoofed or replayed MAVLink packets on the UDP link; malformed packets crashing
  or stalling the app; leakage of the signing key; operator mistakes (dangerous command sent by
  accident, mission outside limits).
- **Out of scope:** link encryption (provided by the radio in real systems — MAVLink signing
  authenticates but does not encrypt), the vehicle's own security, physical device compromise.

## MASVS-STORAGE

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| S-1 | The MAVLink signing key is never stored in plain text: it is encrypted at rest with a key held in Android Keystore. | v0.6 | ⏳ | |
| S-2 | No secrets in source code, resources, build files or the repository. | v0.0 | ✅ | gitleaks pre-commit hook; `.gitignore` excludes keystores and `*.mavkey` |
| S-3 | No sensitive data (keys, signatures) in logs. | v0.6 | ⏳ | |
| S-4 | Backups exclude the signing key and sensitive data (`data_extraction_rules.xml`, `backup_rules.xml`). | v0.6 | ⏳ | |
| S-5 | Missions and flight logs are stored in app-private storage. | v0.5 | ⏳ | |

## MASVS-CRYPTO

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| C-1 | MAVLink 2 signing implemented as specified (SHA-256 based, 48-bit signature, timestamp, link ID). | v0.6 | ⏳ | |
| C-2 | Signing keys are 32 random bytes from a cryptographically secure source. | v0.6 | ⏳ | |
| C-3 | Key rotation supported, with explicit operator confirmation. | v0.6 | ⏳ | |

## MASVS-AUTH

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| A-1 | When signing is required, unsigned packets are rejected and logged. | v0.6 | ⏳ | |
| A-2 | Replayed packets are rejected (monotonic timestamps per system/component/link). | v0.6 | ⏳ | |
| A-3 | Dangerous commands (arm, takeoff, mission change in flight) require explicit operator confirmation. | v0.4 | ⏳ | |

## MASVS-NETWORK

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| N-1 | The UDP endpoint (port, expected vehicle) is configurable; traffic from unexpected system IDs is visible and never merged into the current vehicle. | v0.1 | 🚧 | Port injectable (`UdpTransport`, REQ-005). The first vehicle heard is pinned as the active one and never replaced (#13, `DefaultVehicleRepository.activeSystemId`, REQ-020); other system IDs are kept apart per sysid and shown as an "other vehicle detected" warning in the HUD. **Known limitations until signing (v0.6):** the pin is unauthenticated, so whoever sends the first HEARTBEAT is pinned until the app restarts and the real vehicle then shows only as "other vehicle"; "never merged" holds per system ID only, since a HEARTBEAT with the active system ID from any host is merged (see N-2). If the active vehicle's HEARTBEATs arrive by two routes (direct and through a forwarder), the GCS HEARTBEAT follows whichever came last (#14). **Missing:** an operator setting for the port and the expected vehicle (which also fixes that route flip), and operator selection or confirmation of the active vehicle. |
| N-2 | Link loss is detected (HEARTBEAT timeout) and blocks commands. | v0.1 / v0.4 | 🚧 | HEARTBEAT-timeout detection per system ID done (#12, `DefaultVehicleRepository`, fails safe to LOST if the watchdog stops); while observed, a failed transport is reopened by itself with a bounded backoff (1–5 s); the watchdog does not depend on it, so the vehicle still goes LOST on time and stays LOST until heard again; during the outage the GCS HEARTBEAT is not sent (`NotOpen`) and after reopening it resumes only while the vehicle is still within its timeout; a failure to bind the port on the session's first attempt (another app holds it) is not retried (#25, REQ-019); a link that keeps flapping is not damped yet and a port taken while reopening looks like a first-bind failure in the HUD (#31, v0.2); operator warning: full-screen link-lost emergency in the HUD (#13, `HudViewModelTest`, SITL check against QGroundControl); GCS HEARTBEAT checked in SITL through `tools/sitl/sitl_relay.py` (#14); command blocking v0.4. **GCS HEARTBEAT (#14, REQ-021):** sent only while the active vehicle is CONNECTED (watchdog and rule on the current age, so a watchdog timer delayed by sleep cannot keep it going; tested). Before this, the vehicle never heard a GCS, so its GCS failsafe stayed inactive ("if no GCS is ever connected, the GCS failsafe will remain inactive regardless of parameter settings", ardupilot.org `gcs-failsafe.html`); with it, the failsafe can act on this app. It acts only if `FS_GCS_ENABLE` is set (ArduCopter's default is 0, disabled, `ArduCopter/Parameters.cpp`) and the vehicle is armed, after `FS_GCS_TIMEOUT` (default 5 s) without this HEARTBEAT; when this GCS stops hearing the vehicle it stops announcing itself, so the failsafe also covers a downlink-only loss. **Known limitations:** (1) after the app is hidden (background or locked screen) the HEARTBEAT stops within seconds: on the API 36 emulator the system blocks the app's network after about 5 s (`sendto` fails with `EPERM`, measured in the #14 SITL check), and the app itself closes the link about 10 s after the HUD is hidden (5 s HUD stop timeout + 5 s link stop timeout). An armed vehicle with the failsafe enabled then triggers it `FS_GCS_TIMEOUT` later; a foreground service is planned before real hardware (#28, v0.4). (2) An uplink-only failure (sends fail while receiving works) is only logged: the HUD stays CONNECTED while the vehicle stops hearing this GCS (#29, v0.4). (3) Arrival time is marked when a datagram is read, so HEARTBEATs that waited in the socket buffer while the device slept look fresh on wake and can bring back up to one timeout of CONNECTED (#12). (4) Until signing (v0.6), what counts as "alive" is not authenticated and the source address is not checked, so any host that reaches UDP 14550 can keep a vehicle CONNECTED with a forged unsigned HEARTBEAT (system 1, component 1); and the GCS HEARTBEAT goes to the address of the active system ID's last HEARTBEAT, so a forged HEARTBEAT with that system ID redirects ours to the forger (any host, a negligible 21-byte/s reflection). See C-1, A-2. |
| N-3 | Cleartext HTTP is not used (map tiles over HTTPS). | v0.3 | ⏳ | |
| N-4 | Cursor on Target output can be disabled and only goes to the configured destination. | v0.7 | ⏳ | |

## MASVS-PLATFORM

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| P-1 | Only the launcher activity is exported; no other exported components. | v0.0 | ✅ | `AndroidManifest.xml` |
| P-2 | Only the permissions needed by the current phase are requested. | v0.0 | ✅ | `INTERNET` only, for the UDP vehicle link (`data/vehicle/src/main/AndroidManifest.xml`) |
| P-3 | Sensitive screens (key management) are protected from screenshots/recents. | v0.6 | ⏳ | |

## MASVS-CODE

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| K-1 | The MAVLink parser never crashes on malformed input (fuzz-style tests). | v0.1 | ⏳ | Related (#12): an *unexpected* exception in the link-loss watchdog is not swallowed. It first marks every vehicle LOST and stops the listener, then crashes the process on purpose. |
| K-2 | Inputs from the network are validated before use (lengths, ranges, enums). | v0.1 | ⏳ | |
| K-3 | Missions are validated before upload (altitude, distance from home, geofence, waypoint count). | v0.5 | ⏳ | |
| K-4 | Dependencies are kept up to date (Dependabot) and checked in CI. | v0.0 | ✅ | `.github/dependabot.yml`, `android-ci.yml` |
| K-5 | Release builds are minified and debuggable is off. | v1.0 | ⏳ | |

## MASVS-RESILIENCE

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| R-1 | Anti-tampering / root detection. | — | ➖ | Not in scope for an educational, simulator-only app; noted for completeness. |

## MASVS-PRIVACY

| # | Control | Phase | Status | Evidence / notes |
|---|---|---|---|---|
| V-1 | No analytics or tracking SDKs. | v0.0 | ✅ | No such dependencies |
| V-2 | Location data (vehicle, home, operator) stays on the device unless the operator enables CoT output. | v0.7 | ⏳ | |
