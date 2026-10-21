#!/usr/bin/env python3
"""Make the night map style: OpenFreeMap's liberty recoloured to dark greys, same layers and labels (#73, ADR 0005).

Run from the repository root:

    python tools/map/make_night_style.py

It downloads liberty from OpenFreeMap, keeps its sources, sprite, glyphs and every layer, recolours them and writes
feature/map/src/main/assets/map/liberty-night.json. The tiles and their attribution stay OpenFreeMap's. Then it prints
the colours the vehicle and the labels are drawn on, which ContrastTest checks.

Each kind of paint is recoloured on its own scale, keeping its hue and a little of its saturation, so the night map
reads like the day one: fills and the background dark, lines (roads, rivers, boundaries, rails) mid grey and lighter
than the land, text light on a dark halo. A label with no halo gets one, so it reads over the lighter roads. Road
shields and POI icons are sprites and stay as they are, with their text.
"""
import colorsys
import json
import re
import sys
import urllib.request
from datetime import date
from pathlib import Path

SOURCE = "https://tiles.openfreemap.org/styles/liberty"
# OpenFreeMap answers 403 to an anonymous client and asks for a User-Agent that names the app (openfreemap.org/tos).
USER_AGENT = "GCS-night-style/1.0 (+https://github.com/0winDev/android-ground-control-station)"
OUTPUT = Path("feature/map/src/main/assets/map/liberty-night.json")

# Lightness scales, L' = offset + slope × L, with L the day colour's HSL lightness from 0 to 1.
FILL = (0.04, 0.11)
LINE = (0.22, 0.33)
TEXT = (0.92, -0.50)
HALO_LIGHTNESS = 0.10
SATURATION = {"fill": 0.15, "line": 0.30, "text": 0.60}

# Water keeps more of its blue, so lakes and the sea stand apart from the land at night.
WATER = {"layer": "water", "lightness": 0.20, "saturation": 0.40}

# The low-zoom shaded relief, a raster, is dimmed rather than recoloured.
RASTER_BRIGHTNESS_MAX = 0.35
NEW_HALO_WIDTH = 1.0

# Where the vehicle is drawn: the follow zoom of the map (MapScreen.FOLLOW_ZOOM).
FOLLOW_ZOOM = 17

COLOUR = re.compile(r"^(#[0-9a-fA-F]{3,8}|(rgb|hsl)a?\(.*\))$")


def parse(colour: str) -> tuple[float, float, float, float]:
    """r, g, b and alpha from 0 to 1."""
    colour = colour.strip()
    if colour.startswith("#"):
        digits = colour[1:]
        if len(digits) in (3, 4):
            digits = "".join(c * 2 for c in digits)
        values = [int(digits[i:i + 2], 16) / 255 for i in range(0, len(digits), 2)]
        return (*values[:3], values[3] if len(values) == 4 else 1.0)
    name, args = colour.split("(", 1)
    parts = [p.strip() for p in args.rstrip(")").split(",")]
    alpha = float(parts[3]) if len(parts) == 4 else 1.0
    if name.startswith("rgb"):
        return (*(float(p) / 255 for p in parts[:3]), alpha)
    h, s, l = float(parts[0]) / 360, float(parts[1].rstrip("%")) / 100, float(parts[2].rstrip("%")) / 100
    return (*colorsys.hls_to_rgb(h, l, s), alpha)


def text(r: float, g: float, b: float, a: float) -> str:
    rgb = ",".join(str(round(v * 255)) for v in (r, g, b))
    return f"rgba({rgb},{a:g})" if a < 1 else f"rgb({rgb})"


def recolour(colour: str, kind: str) -> str:
    r, g, b, a = parse(colour)
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    if kind == "halo":
        lightness = HALO_LIGHTNESS
    else:
        offset, slope = {"fill": FILL, "line": LINE, "text": TEXT}[kind]
        lightness = offset + slope * l
    saturation = s * SATURATION.get(kind, 0.0)
    return text(*colorsys.hls_to_rgb(h, min(max(lightness, 0.0), 1.0), saturation), a)


def walk(value, kind: str):
    """[value] with every colour in it recoloured, inside expressions too."""
    if isinstance(value, str):
        return recolour(value, kind) if COLOUR.match(value.strip()) else value
    if isinstance(value, list):
        return [walk(item, kind) for item in value]
    return value


KIND = {
    "background-color": "fill",
    "fill-color": "fill",
    "fill-extrusion-color": "fill",
    "fill-outline-color": "line",
    "line-color": "line",
    "text-color": "text",
    "text-halo-color": "halo",
}


def water(colour: str) -> str:
    r, g, b, a = parse(colour)
    h, _, s = colorsys.rgb_to_hls(r, g, b)
    return text(*colorsys.hls_to_rgb(h, WATER["lightness"], s * WATER["saturation"]), a)


def night(style: dict) -> dict:
    for layer in style["layers"]:
        paint = layer.setdefault("paint", {})
        if layer["id"] == WATER["layer"]:
            paint["fill-color"] = water(paint["fill-color"])
            continue
        for name, kind in KIND.items():
            if name in paint:
                paint[name] = walk(paint[name], kind)
        if layer["type"] == "raster":
            paint["raster-brightness-max"] = RASTER_BRIGHTNESS_MAX
        if layer["type"] == "symbol" and "text-color" in paint and "text-halo-color" not in paint:
            paint["text-halo-color"] = text(*colorsys.hls_to_rgb(0, HALO_LIGHTNESS, 0), 1.0)
            paint["text-halo-width"] = NEW_HALO_WIDTH
    style["name"] = "GCS night (liberty recoloured)"
    style["metadata"] = {
        "gcs:source": SOURCE,
        "gcs:generated": date.today().isoformat(),
        "gcs:script": "tools/map/make_night_style.py",
        "gcs:license": "licenses/BSD-3-Clause-OSM-Liberty.md: OSM Liberty's style JSON, recoloured",
    }
    return style


def at_zoom(value, zoom: float):
    """[value] at [zoom], for the colours and opacities this style interpolates."""
    if isinstance(value, list) and value and value[0] == "interpolate":
        stops = value[3:]
        pairs = list(zip(stops[0::2], stops[1::2]))
        chosen = pairs[0][1]
        for stop, output in pairs:
            if zoom >= stop:
                chosen = output
        return chosen
    return value


def report(style: dict) -> None:
    layers = {layer["id"]: layer.get("paint", {}) for layer in style["layers"]}
    background = parse(layers["background"]["background-color"])

    def over(layer: str) -> str:
        paint = layers[layer]
        r, g, b, a = parse(at_zoom(paint["fill-color"], FOLLOW_ZOOM))
        opacity = a * float(at_zoom(paint.get("fill-opacity", 1.0), FOLLOW_ZOOM))
        blended = [opacity * c + (1 - opacity) * bc for c, bc in zip((r, g, b), background[:3])]
        return "#" + "".join(f"{round(v * 255):02X}" for v in blended)

    print("Backgrounds at zoom", FOLLOW_ZOOM)
    print("  background", "#" + "".join(f"{round(v * 255):02X}" for v in background[:3]))
    for layer in ("water", "park", "landcover_wood", "landuse_residential", "aeroway_fill", "building"):
        print(" ", layer, over(layer))
    print("Labels (text on halo)")
    for layer in ("label_city", "label_village", "poi_r20", "highway-name-minor", "water_name_line_label"):
        paint = layers[layer]
        print(" ", layer, paint["text-color"], "on", paint["text-halo-color"])


def main() -> None:
    request = urllib.request.Request(SOURCE, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response:
        style = json.load(response)
    style = night(style)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8", newline="\n") as out:
        json.dump(style, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)", file=sys.stderr)
    report(style)


if __name__ == "__main__":
    main()
