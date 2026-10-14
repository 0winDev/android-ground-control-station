# MGRS reference points

`mgrs-reference.csv` holds the expected MGRS (1 m, 10 digits) and UTM of a fixed set of points for the geo tests in
`:core:domain` (REQ-027). Expected values come from two independent references that must agree, **never** from our
own code.

## References

| | |
|---|---|
| Date | 2026-10-14 (issue #40) |
| GeographicLib | **GeoConvert 2.7** (C. F. F. Karney), official Windows installer `GeographicLib-2.7-win64.exe` from the project's SourceForge `distrib-C++` folder (released 2025-11-06, MD5 `ae73de1ea9b06886520689e911e0b340`, checked against the published hash). Transverse Mercator from Karney, *Transverse Mercator with an accuracy of a few nanometers*, J. Geodesy 85(8), 475–485 (2011). WGS84 a = 6378137 m, f = 1/298.257223563. MGRS truncated to the requested precision; standard UTM/UPS zones with the Norway and Svalbard exceptions (all from `GeoConvert --help`). |
| GEOTRANS | **mgrs 1.5.4** (PyPI, Howard Butler), which ships an internal copy of the MGRS C code of NGA **GEOTRANS 2.4.2** (from the package description). Needs `packaging`, which 1.5.4 does not declare. |
| Python | 3.14.6 |

## Columns

| Column | Meaning |
|---|---|
| `latitude`, `longitude` | Exactly as given to both tools, so the tests parse the same doubles. |
| `mgrs` | MGRS at 1 m, or `NOT_AVAILABLE` where the references use UPS (outside UTM coverage). |
| `utm_zone`, `utm_hemisphere`, `utm_easting`, `utm_northing` | GeoConvert's UTM to 1 µm (`-u -p 6`); empty when not available. |
| `reason` | Why the point is in the set. |

## Decisions

- **84°N exactly:** GeoConvert uses UTM for −80° ≤ latitude < 84° and UPS from 84°N; GEOTRANS keeps 84°N in UTM
  (`35XMP7666428498` at 84N 25E). The domain follows GeoConvert and says "not available" at 84°N, so at the limit it
  never gives a value one reference rejects. This is the only disagreement the script accepts, and it checks that the
  two references still disagree exactly that way. At −80° both references use UTM.
- **Away from 1 m boundaries:** points that do not test truncation stay at least 1 cm from any 1 m boundary. Exactly on
  a boundary, truncation is ill-conditioned (a nanometre decides the digit), and GEOTRANS 2.4.2 is not accurate to the
  sub-millimetre: a point 0.5 mm under the false northing came out as the next 100 km square there.
- Points given in UTM in the script are converted to latitude/longitude by GeoConvert first (to 1e-14 degrees).

## Regenerate

```bash
python -m venv geo-venv
geo-venv/Scripts/python -m pip install mgrs==1.5.4 packaging
geo-venv/Scripts/python -I tools/geo/mgrs_reference.py "<GeographicLib>/bin/GeoConvert.exe"
```

The script stops if the references disagree anywhere else, and writes the tool versions and the date at the top of
the CSV.
