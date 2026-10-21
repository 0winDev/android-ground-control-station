# 0005. Draw the map from OpenFreeMap's public tiles, with MapLibre's own cache

- Status: Proposed
- Date: 2026-10-21
- Issue: #73

## Context

ADR 0002 chose MapLibre and left the tile source to v0.3. The source must not need a paid key or an account (the
repository is public), must be served over HTTPS (`docs/security.md` N-3), and its terms must allow an open-source
app to use it. The map is a background: the vehicle, its heading, trail and home are what the operator needs, and
they must be drawn even when no tile arrives (REQ-034). Offline maps are v1.0's.

What we know, checked on 2026-10-21:

- [OpenFreeMap](https://openfreemap.org/) serves OpenStreetMap vector tiles and styles from a public instance:
  "no limits on the number of map views or requests", "no registration, no user database, no API keys, and no
  cookies". Its styles `liberty`, `bright`, `positron`, `dark` and `fiord` answer at
  `https://tiles.openfreemap.org/styles/<name>`, and a script must name itself in its User-Agent or gets a 403.
- Attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" is required, and MapLibre adds it from the
  tiles' own `attribution`.
- The [terms](https://openfreemap.org/tos/) do not mention commercial use, keys or attribution. They limit offline
  downloads (3,000 tiles or 50 MB, 4 parallel requests, an identifying User-Agent). They warn that the service "may
  [be discontinued] at any time without notice", with no warranty of availability.
- The [privacy policy](https://openfreemap.org/privacy/) logs user agent, referrer and time with no IP address by
  default, deletes logs after 7 days, and sits behind Cloudflare.
- MapLibre Compose 0.20.0 keeps MapLibre Native's ambient cache: `maplibre-cache.db` in the app's cache directory,
  50 MiB by default (`MapRuntimeOptions`, `maplibreNativeMain`). It needs a render runtime, OpenGL ES or Vulkan,
  both with minSdk 24.

## Decision

We draw the map from OpenFreeMap's public instance over HTTPS. By day the style is OpenFreeMap's `liberty`. By night
it is the same `liberty` recoloured to dark greys by `tools/map/make_night_style.py` and shipped in the app's assets
(`feature/map/src/main/assets/map/liberty-night.json`), on the same tiles, sprite and glyphs, so both palettes show
the same layers and labels. OpenFreeMap allows a modified style if we host its JSON ourselves ("When you use a
customized style, you need to host the style JSON yourself", openfreemap.org/quick_start). `liberty` derives from OSM
Liberty, whose JSON is BSD 3-Clause and whose design is CC BY: its licence ships beside the style
(`assets/licenses/BSD-3-Clause-OSM-Liberty.md`), the style's `metadata` says it is recoloured, and the OpenMapTiles and
OpenStreetMap credit comes with the tiles. `dark` and `fiord` were tried first and dropped: `dark` barely shows roads
at night, and `fiord` is another map, which loses road names, rivers, POIs and shields. On the night style the live
vehicle keeps at least 6.4:1, the greyed one 4.9:1 (6.1:1 on the surface-coloured disc it also sits on) and every label
7:1 on its halo (`ContrastTest`). We keep MapLibre's attribution visible and its default ambient cache (50 MiB,
app-private, never backed up). We download no offline regions until v1.0.

When the style has not loaded after 10 s, as on a network that answers nothing, the map switches to a style with a
background only, inline JSON that needs no network, and stays on it until the palette changes: trying again would
drop the trail with the style each time. The vehicle, heading and home are drawn over the map and the trail is a
layer of whatever style is loaded, so none of them depends on the tiles.

The attribution, "OpenFreeMap © OpenMapTiles Data from OpenStreetMap", is always on the map as plain text, never a
link, so a stray tap in flight never takes the operator out of the app. OpenFreeMap requires the text, not links
(openfreemap.org, "Attribution"); OpenStreetMap allows plain text and asks that the attribution "should not require
individuals to interact with the map … to see the attribution", and that where it does not link "the link must be
provided in an easily locatable part of the web page, software, or application", such as "an 'About' option in a menu"
(osmfoundation.org/wiki/Licence/Attribution_Guidelines). The links live in a Licences dialog opened from the status
bar, shown only while no vehicle is armed; it moves to the settings screen with #74.

We use the OpenGL ES runtime. Vulkan is optional on Android 8 devices and unreliable on the emulator.

## Alternatives considered

### MapTiler, Stadia Maps, Thunderforest
- Pros: an SLA, more styles, offline packs.
- Cons: an API key tied to an account, which a public repository would expose or need a proxy for.
- Why not: the same key problem that ruled out Google Maps and Mapbox (ADR 0002).

### OpenStreetMap's raster tiles (`tile.openstreetmap.org`)
- Pros: the reference data, no key.
- Cons: its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/) forbids heavy use and bulk
  downloads, and raster tiles render worse at fractional zooms.
- Why not: OpenFreeMap serves the same data as vector tiles, without that policy's limits.

### Self-hosted OpenFreeMap or PMTiles
- Pros: availability and privacy under our control.
- Cons: a server to run and pay for.
- Why not: too much for a portfolio project before v1.0's offline work, which can host its own extract.

## Consequences

- Positive:
  - No key, account or cost.
  - Vector tiles, with a night style that matches the theme.
  - The map's absence never hides the vehicle.
- Negative / costs we accept:
  - Tile requests reveal the area on screen to OpenFreeMap and Cloudflare (`docs/security.md` V-2).
  - The service can disappear without notice, which leaves a plain background.
  - The cache keeps viewed areas on the device until evicted.
- Follow-ups:
  - v1.0 offline maps: a downloaded or self-hosted extract within OpenFreeMap's download limits, with a
    User-Agent that names the app.
