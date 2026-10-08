# 0002. Use MapLibre instead of Google Maps

- Status: Accepted
- Date: 2026-10-08
- Issue: #1

## Context

From v0.3 the app shows the vehicle, its heading, trail and home point on a map, and from v0.5 a
waypoint editor. GCS must also work in the field without internet (v1.0: offline maps). The project is
public and maintained by one person, so map access must not depend on paid keys or a billing account.
The UI is Jetpack Compose.

## Decision

We use [MapLibre](https://maplibre.org/) through
[MapLibre Compose](https://maplibre.org/maplibre-compose/getting-started), with the version pinned in
the version catalog because its API is still pre-1.0. The dependency is added in v0.3, not before.
[Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android) serves as a reference
integration.

## Alternatives considered

### Google Maps SDK for Android (+ Maps Compose)
- Pros: mature, stable Compose API, familiar.
- Cons: requires an API key tied to a Google Cloud billing account; proprietary; offline use is
  limited and not under the app's control.
- Why not: keys and billing in a public portfolio repo, and weak offline support for field use.

### Mapbox Maps SDK
- Pros: mature, good offline support.
- Cons: proprietary license and access token required.
- Why not: same key/licensing problem as Google Maps.

### osmdroid
- Pros: open source, offline tiles.
- Cons: View-based (no first-class Compose API), raster-oriented.
- Why not: a worse fit for a Compose-first app than MapLibre.

## Consequences

- Positive: open source (BSD-licensed), no paid keys, vector maps, offline support available,
  Compose-native API.
- Negative / costs we accept: pre-1.0 API that may change between versions (pinned version, upgrades
  as deliberate PRs); we need a tile source whose terms allow our use, decided in v0.3.
- Follow-ups: choose the tile/style source and its offline strategy in the first v0.3 issue.
