#!/usr/bin/env python3
"""Checks a festival map (venue.json) before it goes into the app.

  python3 tools/check_venue.py app/src/main/assets/venue.json

The file has a name, the bounds of the area, the zones, and optionally a meeting point, useful points
and walkways (core/venue/Venue.kt reads it):

  {
    "name": "Festival",
    "bounds": {"minLat": 43.948, "minLon": 28.632, "maxLat": 43.9512, "maxLon": 28.6382},
    "meetingPoint": {"lat": 43.94893, "lon": 28.6351},
    "zones": [
      {"id": "main-stage", "name": "Scena principală", "color": "#E5484D", "type": "stage",
       "polygon": [{"lat": 43.951, "lon": 28.6344}, {"lat": 43.951, "lon": 28.6358}, ...]}
    ],
    "pois": [{"type": "dea", "lat": 43.9495, "lon": 28.6349, "name": "DEA intrare", "number": 3}],
    "paths": [[{"lat": 43.9485, "lon": 28.633}, {"lat": 43.9505, "lon": 28.637}]]
  }

A zone's id travels in reports and messages, so it must stay the same for the whole event and fit in
32 bytes. Corners go around the zone in order. Zones may share a border but not overlap: a point has
to belong to a single zone. A point's number makes it a landmark that reports name in one byte, so it
goes from 1 to 255 and isn't repeated. Exits with 1 and lists the problems if there are any. The same
check runs before gen_staff_keys.py signs an event pack.
"""

import json
import re
import sys

ZONE_BYTES = 32
LANDMARK_MAX = 255
COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")


def _points(polygon):
    return [(p["lat"], p["lon"]) for p in polygon]


def _orientation(a, b, c):
    value = (b[1] - a[1]) * (c[0] - b[0]) - (b[0] - a[0]) * (c[1] - b[1])
    return 0 if abs(value) < 1e-15 else (1 if value > 0 else -1)


def _crosses(p1, p2, q1, q2):
    """True only for a proper crossing; touching at a point or along a shared border doesn't count."""
    o1, o2 = _orientation(p1, p2, q1), _orientation(p1, p2, q2)
    o3, o4 = _orientation(q1, q2, p1), _orientation(q1, q2, p2)
    return o1 * o2 < 0 and o3 * o4 < 0


def _edges(points):
    return [(points[i], points[(i + 1) % len(points)]) for i in range(len(points))]


def _on_edge(point, edges):
    for a, b in edges:
        if _orientation(a, b, point) == 0 and min(a[0], b[0]) <= point[0] <= max(a[0], b[0]) \
                and min(a[1], b[1]) <= point[1] <= max(a[1], b[1]):
            return True
    return False


def _strictly_inside(point, points):
    """Ray casting, like the app's pointInPolygon; a point on the border is not inside."""
    if _on_edge(point, _edges(points)):
        return False
    lat, lon = point
    inside = False
    j = len(points) - 1
    for i in range(len(points)):
        a, b = points[i], points[j]
        if (a[0] > lat) != (b[0] > lat) and lon < (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]) + a[1]:
            inside = not inside
        j = i
    return inside


def _interior_points(points):
    """Points well inside the outline: the middle of each stretch that a line of latitude spends inside it."""
    lats = sorted({p[0] for p in points})
    samples = []
    for low, high in zip(lats, lats[1:]):
        lat = (low + high) / 2
        xs = sorted(a[1] + (lat - a[0]) * (b[1] - a[1]) / (b[0] - a[0])
                    for a, b in _edges(points) if (a[0] > lat) != (b[0] > lat))
        samples += [(lat, (xs[i] + xs[i + 1]) / 2) for i in range(0, len(xs) - 1, 2)]
    return samples


def _area(points):
    return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in _edges(points))) / 2


def problems(venue):
    found = []
    try:
        bounds = venue["bounds"]
        min_lat, min_lon = bounds["minLat"], bounds["minLon"]
        max_lat, max_lon = bounds["maxLat"], bounds["maxLon"]
        zones = venue["zones"]
        if not venue.get("name"):
            found.append("the venue has no name")
    except (KeyError, TypeError) as e:
        return [f"missing field: {e}"]
    if not (min_lat < max_lat and min_lon < max_lon):
        found.append("bounds: min must be below max")

    def outside(lat, lon):
        return not (min_lat <= lat <= max_lat and min_lon <= lon <= max_lon)

    seen = set()
    shapes = []
    for index, zone in enumerate(zones):
        zone_id = zone.get("id", "")
        label = f"zone {zone_id or index}"
        if not zone_id:
            found.append(f"{label}: missing id")
        elif zone_id in seen:
            found.append(f"{label}: duplicate id")
        seen.add(zone_id)
        if len(zone_id.encode("utf-8")) > ZONE_BYTES:
            found.append(f"{label}: the id must fit in {ZONE_BYTES} bytes")
        if not zone.get("name"):
            found.append(f"{label}: missing name")
        if not COLOR.match(zone.get("color", "")):
            found.append(f"{label}: color must look like #RRGGBB")
        try:
            points = _points(zone["polygon"])
        except (KeyError, TypeError):
            found.append(f"{label}: missing or broken polygon")
            continue
        if len(points) < 3:
            found.append(f"{label}: a polygon needs at least 3 corners")
            continue
        if any(outside(lat, lon) for lat, lon in points):
            found.append(f"{label}: a corner is outside bounds")
        if _area(points) == 0:
            found.append(f"{label}: the polygon has no area")
        edges = _edges(points)
        if any(_crosses(*edges[i], *edges[j]) for i in range(len(edges)) for j in range(i + 2, len(edges))
               if not (i == 0 and j == len(edges) - 1)):
            found.append(f"{label}: the outline crosses itself; list the corners in order around the zone")
        shapes.append((label, points))

    for i in range(len(shapes)):
        for j in range(i + 1, len(shapes)):
            (name_a, a), (name_b, b) = shapes[i], shapes[j]
            crossing = any(_crosses(*ea, *eb) for ea in _edges(a) for eb in _edges(b))
            # corners catch a zone poking into another; inner points, one lying on top of another along its edges
            nested = any(_strictly_inside(p, b) for p in a + _interior_points(a)) or \
                any(_strictly_inside(p, a) for p in b + _interior_points(b))
            if crossing or nested:
                found.append(f"{name_a} and {name_b} overlap; a point must belong to a single zone")

    meeting = venue.get("meetingPoint")
    if meeting is not None:
        try:
            if outside(meeting["lat"], meeting["lon"]):
                found.append("the meeting point is outside bounds")
        except (KeyError, TypeError):
            found.append("the meeting point needs lat and lon")

    numbers = set()
    for index, poi in enumerate(venue.get("pois", []), start=1):
        label = f"point {index} ({poi.get('name') or poi.get('type') or '?'})"
        if not poi.get("type"):
            found.append(f"{label}: missing type")
        try:
            if outside(poi["lat"], poi["lon"]):
                found.append(f"{label}: outside bounds")
        except (KeyError, TypeError):
            found.append(f"{label}: needs lat and lon")
        number = poi.get("number", 0)
        if number:
            if not (isinstance(number, int) and 1 <= number <= LANDMARK_MAX):
                found.append(f"{label}: a landmark number goes from 1 to {LANDMARK_MAX}")
            elif number in numbers:
                found.append(f"{label}: number {number} is already taken; reports name a landmark by it")
            numbers.add(number)

    for index, path in enumerate(venue.get("paths", []), start=1):
        try:
            points = _points(path)
        except (KeyError, TypeError):
            found.append(f"path {index}: points need lat and lon")
            continue
        if len(points) < 2:
            found.append(f"path {index}: a walkway needs at least 2 points")
        elif any(outside(lat, lon) for lat, lon in points):
            found.append(f"path {index}: a point is outside bounds")
    return found


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as f:
        try:
            venue = json.load(f)
        except json.JSONDecodeError as e:
            sys.exit(f"not valid JSON: {e}")
    found = problems(venue)
    for problem in found:
        print(problem)
    if found:
        sys.exit(1)
    print(f"ok: {len(venue['zones'])} zones")


if __name__ == "__main__":
    main()
