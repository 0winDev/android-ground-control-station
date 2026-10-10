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
| N-1 | The UDP endpoint (port, expected vehicle) is configurable; traffic from unexpected system IDs is visible and never merged into the current vehicle. | v0.1 | ⏳ | |
| N-2 | Link loss is detected (HEARTBEAT timeout) and blocks commands. | v0.1 / v0.4 | ⏳ | |
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
| K-1 | The MAVLink parser never crashes on malformed input (fuzz-style tests). | v0.1 | ⏳ | |
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
