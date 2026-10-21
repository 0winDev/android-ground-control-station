# Architecture decision records

Decisions someone will ask "why?" about. Format and rules: the `adr` skill
(`.claude/skills/adr/SKILL.md`). Accepted ADRs are never edited; a new ADR supersedes them.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-own-mavlink-codec.md) | Write our own MAVLink v2 codec instead of using a library | Accepted |
| [0002](0002-maplibre-over-google-maps.md) | Use MapLibre instead of Google Maps | Accepted |
| [0003](0003-ardupilot-only-until-v1.md) | Support only ArduPilot until v1.0 | Accepted |
| [0004](0004-domain-layer.md) | Add a pure Kotlin domain layer (`:core:domain`) between features and data | Accepted |
| [0005](0005-openfreemap-tiles.md) | Draw the map from OpenFreeMap's public tiles, with MapLibre's own cache | Proposed |
| [0006](0006-flight-log-is-a-black-box.md) | Record every MAVLink 2 frame as received; validate the log when it is read | Proposed |
