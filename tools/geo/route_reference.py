#!/usr/bin/env python3
"""Reference distances for the route geofence (#150), from GeographicLib's WGS84 geodesics.

Offline tool: GeographicLib (C. F. F. Karney, "Algorithms for geodesics", J. Geodesy 87, 43-55, 2013) gives the
expected values; the app's own code is never used here. For each home it puts a point at a distance and bearing with
Geodesic.Direct, rounds it to 1e-7 degrees as MISSION_ITEM_INT carries it, and measures it back with Geodesic.Inverse.

It also measures the largest error of the app's formula (a local plane with WGS84's radii of curvature at the mean
latitude, re-implemented here) against GeographicLib up to 500 m, at every bearing and latitude listed.

Usage:
  py -m pip install --user geographiclib==2.1
  py tools/geo/route_reference.py
"""

import math

from geographiclib.geodesic import Geodesic

WGS84 = Geodesic.WGS84
A = WGS84.a
E2 = WGS84.f * (2 - WGS84.f)

HOMES = {
    "equator": (0.0, 10.0),
    "SITL": (-35.3632621, 149.1652374),
    "45N": (45.0, 7.0),
    "70N": (70.0, 25.0),
    "antimeridian": (10.0, 179.9999),
}
DISTANCES = (100.0, 499.99, 500.01)
BEARINGS = (0.0, 90.0, 180.0, 270.0, 45.0)


def rounded(degrees):
    return round(degrees * 1e7) / 1e7


def plane_distance(lat0, lon0, lat, lon):
    phi = math.radians((lat0 + lat) / 2)
    w = math.sqrt(1 - E2 * math.sin(phi) ** 2)
    meridional = A * (1 - E2) / w ** 3
    normal = A / w
    d_lon = (lon - lon0 + 180) % 360 - 180
    north = math.radians(lat - lat0) * meridional
    east = math.radians(d_lon) * normal * math.cos(phi)
    return math.hypot(north, east)


def main():
    print("home\tbearing\tasked m\tlatitude\tlongitude\tGeographicLib m")
    for name, (lat0, lon0) in HOMES.items():
        for distance in DISTANCES:
            for bearing in BEARINGS:
                point = WGS84.Direct(lat0, lon0, bearing, distance)
                lat, lon = rounded(point["lat2"]), rounded(point["lon2"])
                s12 = WGS84.Inverse(lat0, lon0, lat, lon)["s12"]
                print(f"{name}\t{bearing:g}\t{distance:g}\t{lat:.7f}\t{lon:.7f}\t{s12:.6f}")
    worst = 0.0
    for lat0 in range(-80, 81, 5):
        for bearing in range(0, 360, 5):
            for distance in (50.0, 250.0, 500.0):
                point = WGS84.Direct(lat0, 0.0, bearing, distance)
                error = abs(plane_distance(lat0, 0.0, point["lat2"], point["lon2"]) - distance)
                worst = max(worst, error)
    print(f"largest plane error up to 500 m, latitudes -80..80: {worst * 1000:.4f} mm")


if __name__ == "__main__":
    main()
