# Architecture decision records

Decisions someone will ask "why?" about. Format and rules: the `adr` skill
(`.claude/skills/adr/SKILL.md`). Accepted ADRs are never edited; a new ADR supersedes them.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-own-mavlink-codec.md) | Write our own MAVLink v2 codec instead of using a library | Proposed |
| [0002](0002-maplibre-over-google-maps.md) | Use MapLibre instead of Google Maps | Proposed |
| [0003](0003-ardupilot-only-until-v1.md) | Support only ArduPilot until v1.0 | Proposed |
| [0004](0004-domain-layer.md) | Add a pure Kotlin domain layer (`:core:domain`) between features and data | Proposed |
