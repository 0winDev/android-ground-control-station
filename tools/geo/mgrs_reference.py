#!/usr/bin/env python3
"""Generate the MGRS reference points for the geo tests in :core:domain.

Offline tool, like tools/fixtures/derive_fixtures.py. Two independent references convert every point and must
agree; our own code is never used here:

  GeographicLib GeoConvert   C++ utility by C. F. F. Karney (transverse Mercator from Karney 2011, MGRS with the
                             Norway and Svalbard exceptions, truncated to the requested precision)
  mgrs (PyPI)                Python wrapper around NGA GEOTRANS's MGRS C code

Writes <out>/mgrs-reference.csv with, for each point: the latitude and longitude exactly as given to both tools,
the MGRS at 1 m (10 digits) or NOT_AVAILABLE where the references fall back to UPS (outside UTM coverage), the UTM
zone, hemisphere, easting and northing from GeoConvert to 1 micrometre, and why the point is there.

The script stops with an error if the two references disagree on any point, except the one known disagreement in
KNOWN_DISAGREEMENTS, which must disagree exactly as recorded there.

Some points are given in UTM and converted to latitude/longitude by GeoConvert first (to 1e-14 degrees, about a
nanometre), to land 1 cm under a 1 m or 100 km boundary, or on letters next to the skipped I and O. Points are kept
at least 1 cm away from any 1 m boundary they do not test: there truncation is ill-conditioned, and GEOTRANS 2.4.2
is not accurate to the sub-millimetre.
"""

import argparse
import csv
import datetime
import importlib.metadata
import subprocess
import sys
from pathlib import Path

import mgrs

NOT_AVAILABLE = "NOT_AVAILABLE"

# (latitude, longitude, reason): strings, passed unchanged to both tools and written unchanged to the CSV, so the
# tests parse exactly the same doubles.
GEOGRAPHIC_POINTS = [
    ("-35.3633514", "149.1652411", "ArduPilot SITL home (CMAC, Canberra), as decoded in the telemetry tests"),
    ("45.0", "9.0", "on the central meridian of zone 32"),
    ("45.0", "11.9999999", "far from the central meridian, just inside zone 32's east edge"),
    ("10.0", "-0.0000001", "just west of 0 degrees: zone 30"),
    ("10.0", "0.0", "0 degrees: zone 31"),
    ("10.0", "5.9999999", "just west of 6 degrees: zone 31"),
    ("10.0", "6.0", "6 degrees: zone 32"),
    ("0.0", "10.0", "the equator: band N, northing 0"),
    ("-0.0000001", "10.0", "just south of the equator: band M, northing just under 10 000 km"),
    ("7.9999999", "10.0", "just under 8N: band N"),
    ("8.0", "10.0", "8N: band P"),
    ("-8.0", "10.0", "8S: band M"),
    ("-8.0000001", "10.0", "just south of 8S: band L"),
    ("71.9999999", "25.0", "just under 72N: band W"),
    ("72.0", "25.0", "72N: band X (12 degrees tall), Svalbard zone 35X"),
    ("83.9999999", "25.0", "just under 84N: top of band X"),
    ("84.0", "25.0", "84N: UPS for GeoConvert, UTM 35X for GEOTRANS; not available here (see the README)"),
    ("84.0000001", "25.0", "just north of 84N: UPS"),
    ("-80.0", "10.0", "80S: the southern limit of UTM, band C"),
    ("-80.0000001", "10.0", "just south of 80S: UPS"),
    ("55.9999999", "3.5", "just under 56N at 3.5E: zone 31U, below the Norway exception"),
    ("56.0", "2.9999999", "56N just west of 3E: zone 31V"),
    ("56.0", "3.0", "56N at 3E: zone 32V (Norway exception)"),
    ("63.9999999", "3.5", "just under 64N at 3.5E: still 32V"),
    ("64.0", "3.5", "64N at 3.5E: zone 31W, no exception"),
    ("60.0", "11.9999999", "inside 32V near its east edge"),
    ("78.0", "8.9999999", "Svalbard: zone 31X up to 9E"),
    ("78.0", "9.0", "Svalbard: zone 33X from 9E"),
    ("78.0", "20.9999999", "Svalbard: zone 33X up to 21E"),
    ("78.0", "21.0", "Svalbard: zone 35X from 21E"),
    ("78.0", "32.9999999", "Svalbard: zone 35X up to 33E"),
    ("78.0", "33.0", "Svalbard: zone 37X from 33E"),
    ("78.0", "41.9999999", "Svalbard: zone 37X up to 42E"),
    ("78.0", "42.0", "Svalbard: zone 38X from 42E"),
    ("71.9999999", "8.0", "just under 72N at 8E: zone 32W, no Svalbard exception"),
    ("-33.8688", "151.2093", "southern hemisphere (Sydney): false northing"),
    ("-54.8", "-68.3", "southern hemisphere, west longitude (Ushuaia)"),
    ("-77.85", "166.67", "band C (McMurdo)"),
    ("30.0", "-175.766", "zone 1: 100 km letter set 1"),
    ("30.0", "-169.766", "zone 2: 100 km letter set 2"),
    ("30.0", "-163.766", "zone 3: 100 km letter set 3"),
    ("30.0", "-157.766", "zone 4: 100 km letter set 4"),
    ("30.0", "-151.766", "zone 5: 100 km letter set 5"),
    ("30.0", "-145.766", "zone 6: 100 km letter set 6"),
    ("10.0", "179.9999999", "just west of 180: zone 60"),
    ("10.0", "180.0", "180: zone 1, as -180"),
    ("10.0", "-180.0", "-180: zone 1"),
    ("85.0", "0.0", "north polar: UPS"),
    ("-85.0", "0.0", "south polar: UPS"),
    ("90.0", "0.0", "north pole: UPS"),
    ("-90.0", "0.0", "south pole: UPS"),
]

# The only point where the references differ by definition: GeographicLib uses UTM for -80 <= latitude < 84 and UPS
# from 84N, GEOTRANS includes 84N in UTM (35X). The domain follows GeographicLib (decided in #40): exactly at
# the limit it says "not available" rather than give a value one reference rejects. Maps the point to what each
# reference must return; anything else still stops the script.
KNOWN_DISAGREEMENTS = {
    ("84.0", "25.0"): ("ZCA8177195739", "35XMP7666428498"),
}

# (zone and hemisphere, easting, northing, reason): converted to latitude/longitude by GeoConvert first.
UTM_POINTS = [
    ("31n", "500123.99", "4500456.99", "1 cm under a 1 m boundary in easting and northing: truncated, not rounded"),
    ("31n", "599999.99", "4599999.99", "1 cm under a 100 km boundary in easting and northing: truncated, not rounded"),
    ("32n", "550000.5", "4500000.5", "zone 32 (set 2), easting column letter N, just before the skipped O"),
    ("32n", "650000.5", "4500000.5", "zone 32 (set 2), easting column letter P, just after the skipped O"),
    ("31n", "500000.5", "750000.5", "row letter H, just before the skipped I"),
    ("31n", "500000.5", "850000.5", "row letter J, just after the skipped I"),
    ("31n", "500000.5", "1250000.5", "row letter N, just before the skipped O"),
    ("31n", "500000.5", "1350000.5", "row letter P, just after the skipped O"),
    ("32n", "500000.5", "650000.5", "even zone: row letters start 5 letters later (M here, G in an odd zone)"),
    ("33s", "500000.5", "9999999.99", "southern hemisphere, 1 cm under the 10 000 km false northing"),
]


def run_geoconvert(geoconvert: Path, args: list[str], lines: list[str]) -> list[str]:
    """Runs GeoConvert once on [lines], one output line per input line; any ERROR stops the script."""
    result = subprocess.run(
        [str(geoconvert), *args],
        input="\n".join(lines) + "\n",
        capture_output=True,
        text=True,
        check=False,
    )
    output = result.stdout.splitlines()
    if len(output) != len(lines):
        sys.exit(f"GeoConvert {args}: {len(lines)} lines in, {len(output)} out\n{result.stdout}{result.stderr}")
    errors = [f"{line} -> {out}" for line, out in zip(lines, output) if out.startswith("ERROR")]
    if errors:
        sys.exit("GeoConvert errors:\n" + "\n".join(errors))
    return output


def geoconvert_version(geoconvert: Path) -> str:
    out = subprocess.run([str(geoconvert), "--version"], capture_output=True, text=True, check=True).stdout
    # "<path>: GeographicLib version 2.7" -> "2.7"
    return out.strip().rsplit(" ", 1)[-1]


def is_ups(geoconvert_mgrs: str) -> bool:
    """UPS MGRS has no zone number: it starts with A, B, Y or Z (GeoConvert pads it with spaces)."""
    return not geoconvert_mgrs.strip()[0].isdigit()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("geoconvert", type=Path, help="path to GeographicLib's GeoConvert executable")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "core/domain/src/test/resources/geo",
        help="output folder (default: core/domain/src/test/resources/geo)",
    )
    options = parser.parse_args()

    # UTM points to latitude/longitude, 9 + 5 = 14 decimals (about a nanometre).
    utm_lines = [f"{zone} {easting} {northing}" for zone, easting, northing, _ in UTM_POINTS]
    derived = run_geoconvert(options.geoconvert, ["-g", "-p", "9"], utm_lines)
    points = list(GEOGRAPHIC_POINTS)
    for (zone, easting, northing, reason), geographic in zip(UTM_POINTS, derived):
        latitude, longitude = geographic.split()
        points.append((latitude, longitude, f"from UTM {zone} {easting} {northing}: {reason}"))

    lines = [f"{latitude} {longitude}" for latitude, longitude, _ in points]
    geoconvert_mgrs = run_geoconvert(options.geoconvert, ["-m", "-p", "0"], lines)
    geoconvert_utm = run_geoconvert(options.geoconvert, ["-u", "-p", "6"], lines)
    converter = mgrs.MGRS()

    rows = []
    disagreements = []
    for (latitude, longitude, reason), gc_mgrs, gc_utm in zip(points, geoconvert_mgrs, geoconvert_utm):
        nga_mgrs = converter.toMGRS(float(latitude), float(longitude), MGRSPrecision=5)
        if isinstance(nga_mgrs, bytes):
            nga_mgrs = nga_mgrs.decode("ascii")
        known = KNOWN_DISAGREEMENTS.get((latitude, longitude))
        if known is not None:
            if (gc_mgrs.strip(), nga_mgrs.strip()) != known:
                disagreements.append(f"{latitude} {longitude}: not the known disagreement {known}")
        elif gc_mgrs.strip() != nga_mgrs.strip():
            disagreements.append(f"{latitude} {longitude}: GeoConvert {gc_mgrs.strip()} vs mgrs {nga_mgrs.strip()}")
        if is_ups(gc_mgrs):
            rows.append([latitude, longitude, NOT_AVAILABLE, "", "", "", "", reason])
            continue
        zone_hemisphere, easting, northing = gc_utm.split()
        rows.append(
            [
                latitude,
                longitude,
                gc_mgrs.strip(),
                zone_hemisphere[:-1],
                zone_hemisphere[-1],
                easting,
                northing,
                reason,
            ]
        )

    if disagreements:
        sys.exit("The references disagree:\n" + "\n".join(disagreements))

    options.out.mkdir(parents=True, exist_ok=True)
    target = options.out / "mgrs-reference.csv"
    with target.open("w", newline="\n", encoding="utf-8") as file:
        file.write(f"# GeographicLib GeoConvert {geoconvert_version(options.geoconvert)}\n")
        file.write(
            f"# mgrs {importlib.metadata.version('mgrs')} (PyPI), with its internal copy of NGA GEOTRANS 2.4.2 MGRS C code\n"
        )
        file.write(f"# Generated {datetime.date.today().isoformat()} by tools/geo/mgrs_reference.py\n")
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(
            ["latitude", "longitude", "mgrs", "utm_zone", "utm_hemisphere", "utm_easting", "utm_northing", "reason"]
        )
        writer.writerows(rows)
    print(f"{len(rows)} points, both references agree: {target}")


if __name__ == "__main__":
    main()
