#!/usr/bin/env python3
"""Checks a festival map (venue.json) before it goes into the app.

  python3 tools/check_venue.py app/src/main/assets/venue.json

The file has a name, the bounds of the area, the zones, and optionally a meeting point, useful points,
walkways, water, fences with their gates, blocked areas, the landmarks fit for meeting and the
evacuation plans (core/venue/Venue.kt reads it):

  {
    "name": "Festival",
    "bounds": {"minLat": 43.948, "minLon": 28.632, "maxLat": 43.9512, "maxLon": 28.6382},
    "meetingPoint": {"lat": 43.94893, "lon": 28.6351},
    "zones": [
      {"id": "main-stage", "name": "Scena principală", "color": "#E5484D", "type": "stage",
       "polygon": [{"lat": 43.951, "lon": 28.6344}, {"lat": 43.951, "lon": 28.6358}, ...]}
    ],
    "pois": [{"type": "dea", "lat": 43.9495, "lon": 28.6349, "name": "DEA intrare", "number": 3}],
    "paths": [[{"lat": 43.9485, "lon": 28.633}, {"lat": 43.9505, "lon": 28.637}]],
    "water": [[{"lat": 43.94, "lon": 28.641}, {"lat": 43.96, "lon": 28.642}, {"lat": 43.96, "lon": 28.66}]],
    "barriers": [[{"lat": 43.948, "lon": 28.632}, {"lat": 43.951, "lon": 28.632}]],
    "gates": [{"lat": 43.9495, "lon": 28.632, "name": "Poarta vest", "number": 2, "staffOnly": false}],
    "blocked": [{"access": "staff", "name": "Backstage", "polygon": [{"lat": 43.9509, "lon": 28.6347}, ...]}],
    "meetingLandmarks": [3],
    "evacuation": [{"id": 1, "name": "Plan A", "exits": {"main-stage": {"main": 4, "alternative": 2}}}]
  }

A zone's id travels in reports and messages, so it must stay the same for the whole event and fit in
32 bytes. Corners go around the zone in order. Zones may share a border but not overlap: a point has
to belong to a single zone. A point's number makes it a landmark that reports name in one byte, so it
goes from 1 to 255 and isn't repeated. Water may reach past the bounds (the sea beside the site), but no zone,
point or gate may stand in it, since the app drops a fix inside water. A fence is crossed only at a
gate, so a gate stands within 5 m of a fence; a gate's number names the useful point it stands at.
A blocked area is "staff" (backstage, VIP) or "none" (technical). The meeting landmarks are numbers
of points. An evacuation plan has an id from 1 to 255 that no other plan has and gives every zone a
main exit and, if it can, an alternative: numbers of points of type exit, two different ones, and
the plan as a whole uses at least two exits. Exits with 1 and lists the problems if there are any.
The same check runs before gen_staff_keys.py signs an event pack.
"""

import json
import math
import re
import sys

ZONE_BYTES = 32
LANDMARK_MAX = 255
PLAN_MAX = 255
GATE_REACH_M = 5
ACCESS = ("staff", "none")
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


def _to_segment(p, a, b):
    """Metres from p to the segment a-b, on a flat projection around p."""
    scale = math.cos(math.radians(p[0]))

    def xy(q):
        return (q[1] - p[1]) * 111_320.0 * scale, (q[0] - p[0]) * 111_320.0

    (ax, ay), (bx, by) = xy(a), xy(b)
    dx, dy = bx - ax, by - ay
    length = dx * dx + dy * dy
    t = 0.0 if length == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / length))
    return math.hypot(ax + t * dx, ay + t * dy)


def _line(found, label, line, outside, closed=False):
    """The points of a line or a polygon, or None once the reason it can't be read is noted."""
    try:
        points = _points(line)
    except (KeyError, TypeError):
        found.append(f"{label}: points need lat and lon")
        return None
    least = 3 if closed else 2
    if len(points) < least:
        found.append(f"{label}: needs at least {least} points")
        return None
    if outside is not None and any(outside(lat, lon) for lat, lon in points):
        found.append(f"{label}: a point is outside bounds")
    return points


def _evacuation_problems(venue, exits):
    found = []
    zone_ids = [z.get("id", "") for z in venue.get("zones", [])]
    ids = [plan.get("id") for plan in venue.get("evacuation", [])]
    for index, plan in enumerate(venue.get("evacuation", []), start=1):
        plan_id = plan.get("id")
        label = f"evacuation plan {plan_id if plan_id is not None else index}"
        if not (isinstance(plan_id, int) and 1 <= plan_id <= PLAN_MAX):
            found.append(f"{label}: the id goes from 1 to {PLAN_MAX}")
        elif ids.count(plan_id) > 1:
            found.append(f"{label}: another plan has the same id")
        if not plan.get("name"):
            found.append(f"{label}: missing name")
        routes = plan.get("exits")
        if not isinstance(routes, dict):
            found.append(f"{label}: needs exits, by zone id")
            continue
        for zone_id in zone_ids:
            if zone_id not in routes:
                found.append(f"{label}: zone {zone_id} has no exit")
        used = set()
        for zone_id, route in routes.items():
            if zone_id not in zone_ids:
                found.append(f"{label}: {zone_id} is not a zone of the venue")
            if not isinstance(route, dict):
                found.append(f"{label}, zone {zone_id}: needs a main exit")
                continue
            main, alternative = route.get("main"), route.get("alternative", 0)
            if main not in exits:
                found.append(f"{label}, zone {zone_id}: main exit {main} is not the number of an exit point")
            if alternative and alternative not in exits:
                found.append(f"{label}, zone {zone_id}: alternative exit {alternative} is not the number of an exit point")
            if alternative and alternative == main:
                found.append(f"{label}, zone {zone_id}: the alternative is the main exit again")
            used.update(n for n in (main, alternative) if n)
        if len(used) < 2:
            found.append(f"{label}: every zone leaves through a single exit")
    return found


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

    numbered = {p.get("number"): p for p in venue.get("pois", []) if isinstance(p, dict) and p.get("number")}

    waters = []
    for index, water in enumerate(venue.get("water", []), start=1):
        label = f"water {index}"
        points = _line(found, label, water, None, closed=True)
        if points is None:
            continue
        if _area(points) == 0:
            found.append(f"{label}: the polygon has no area")
        waters.append(points)
        for name, zone in shapes:
            if any(_strictly_inside(p, points) for p in zone + _interior_points(zone)) or \
                    any(_strictly_inside(p, zone) for p in points):
                found.append(f"{label} covers {name}; a fix there would be dropped")
        for poi in venue.get("pois", []):
            try:
                if _strictly_inside((poi["lat"], poi["lon"]), points):
                    found.append(f"{label} covers point {poi.get('name') or poi.get('type')}")
            except (KeyError, TypeError):
                pass

    fences = []
    for index, barrier in enumerate(venue.get("barriers", []), start=1):
        points = _line(found, f"barrier {index}", barrier, outside)
        if points is not None:
            fences.append(points)

    for index, gate in enumerate(venue.get("gates", []), start=1):
        label = f"gate {index} ({gate.get('name') or '?'})"
        try:
            point = (gate["lat"], gate["lon"])
        except (KeyError, TypeError):
            found.append(f"{label}: needs lat and lon")
            continue
        if outside(*point):
            found.append(f"{label}: outside bounds")
        number = gate.get("number", 0)
        if number and number not in numbered:
            found.append(f"{label}: number {number} is not the number of a point")
        reach = min((_to_segment(point, a, b) for line in fences for a, b in zip(line, line[1:])), default=None)
        if reach is None or reach > GATE_REACH_M:
            found.append(f"{label}: not on a barrier; a gate stands within {GATE_REACH_M} m of a fence")
        if any(_strictly_inside(point, points) for points in waters):
            found.append(f"{label}: in the water")

    for index, area in enumerate(venue.get("blocked", []), start=1):
        label = f"blocked area {index} ({area.get('name') or '?'})"
        if area.get("access", "staff") not in ACCESS:
            found.append(f'{label}: access is "staff" or "none"')
        points = _line(found, label, area.get("polygon"), outside, closed=True)
        if points is not None and _area(points) == 0:
            found.append(f"{label}: the polygon has no area")

    for number in venue.get("meetingLandmarks", []):
        if number not in numbered:
            found.append(f"meeting landmark {number} is not the number of a point")

    exits = {n for n, poi in numbered.items() if poi.get("type") == "exit"}
    found += _evacuation_problems(venue, exits)
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
