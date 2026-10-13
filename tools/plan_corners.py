#!/usr/bin/env python3
"""Places the organiser's illustrated site plan on the map: the four corners for the event pack's "plan".

  python3 tools/plan_corners.py plan.png points.csv --out plan.json

points.csv has one control point per line, a spot you can find both on the plan and on the ground (a gate,
the corner of a stage, a toilet block): its pixel on the plan, column and row from the top-left corner, then
its latitude and longitude, measured on site or read off a satellite map:

  # x, y, lat, lon
  412, 96, 43.95091, 28.63402
  1630, 120, 43.95085, 28.63761
  1598, 1012, 43.94822, 28.63749
  380, 990, 43.94830, 28.63395

At least five points, spread over the whole plan: points on or near one line are refused. The plan is fitted to
them with an affine transform (a drawn plan is a rectangle on the ground, perhaps stretched or sheared, never seen
in perspective) by least squares, in metres around the points' middle. Each point's residual is how far the fit
puts it from where it was measured; with any residual over 5 m the fit is refused and nothing is written:
a wrong point, or a plan drawn out of scale. A fit bends toward a wrong point, so each point is also left out in
turn and predicted from the others; one that lands over 10 m from where it was measured is refused too. Otherwise it writes {"png", "topLeft", "topRight",
"bottomRight", "bottomLeft"}, what gen_staff_keys.py pack --plan puts in the pack. The PNG goes in as it is,
at most 2 MiB. Steps for an organiser: tools/plan_corners.md.
"""

import argparse
import base64
import json
import math
import struct
import sys
from pathlib import Path

MAX_RESIDUAL_M = 5.0
PLAN_MAX_BYTES = 2 * 1024 * 1024
MIN_POINTS = 5
# a point left out and predicted from the others may be off by this much: the others extrapolate, so it is looser
MAX_LEAVE_ONE_OUT_M = 10.0
# the narrow side of the points' spread over the long one: below this they lie along a line
MIN_SPREAD = 0.15
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# the app's distanceMeters (core/venue/Venue.kt): accurate enough within one site
METERS_PER_DEGREE = 111_320.0


class FitError(Exception):
    pass


def png_size(data: bytes) -> tuple[int, int]:
    """Width and height from the IHDR chunk, which a PNG always has first."""
    if len(data) < 24 or not data.startswith(PNG_SIGNATURE) or data[12:16] != b"IHDR":
        raise FitError("not a PNG")
    width, height = struct.unpack(">II", data[16:24])
    if width == 0 or height == 0:
        raise FitError("a PNG without pixels")
    return width, height


def read_points(text: str) -> list[tuple[float, float, float, float]]:
    points = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = [p.strip() for p in line.replace(";", ",").split(",")]
        if len(parts) != 4:
            raise FitError(f"line {number}: x, y, lat, lon expected")
        try:
            x, y, lat, lon = (float(p) for p in parts)
        except ValueError:
            raise FitError(f"line {number}: not a number") from None
        if not all(math.isfinite(v) for v in (x, y, lat, lon)):
            raise FitError(f"line {number}: not a finite number")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise FitError(f"line {number}: latitude or longitude out of range")
        points.append((x, y, lat, lon))
    return points


def check_spread(points) -> None:
    """Refuses points that lie along a line on the plan: the fit would turn and stretch the plan at will."""
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    sxx = sum((p[0] - mx) ** 2 for p in points) / n
    syy = sum((p[1] - my) ** 2 for p in points) / n
    sxy = sum((p[0] - mx) * (p[1] - my) for p in points) / n
    half_trace = (sxx + syy) / 2
    gap = math.sqrt(max(half_trace ** 2 - (sxx * syy - sxy ** 2), 0.0))
    major, minor = half_trace + gap, max(half_trace - gap, 0.0)
    if major <= 0 or math.sqrt(minor / major) < MIN_SPREAD:
        raise FitError("the points are on or near one line: spread them over the plan, one near each corner")


def solve3(m: list[list[float]], v: list[float]) -> list[float]:
    """m · s = v for a 3x3 m, by Gaussian elimination with partial pivoting."""
    a = [row[:] + [b] for row, b in zip(m, v)]
    for col in range(3):
        pivot = max(range(col, 3), key=lambda r: abs(a[r][col]))
        if abs(a[pivot][col]) < 1e-9:
            raise FitError("the points are on one line: spread them over the plan")
        a[col], a[pivot] = a[pivot], a[col]
        for r in range(col + 1, 3):
            f = a[r][col] / a[col][col]
            for c in range(col, 4):
                a[r][c] -= f * a[col][c]
    s = [0.0, 0.0, 0.0]
    for r in (2, 1, 0):
        s[r] = (a[r][3] - sum(a[r][c] * s[c] for c in range(r + 1, 3))) / a[r][r]
    return s


class Fit:
    """Pixel to metres east and north of (lat0, lon0), and back to latitude and longitude."""

    def __init__(self, points, leave_one_out=True):
        if not all(math.isfinite(v) for p in points for v in p):
            raise FitError("a control point is not a finite number")
        # the fits that leave a point out have one fewer
        needed = MIN_POINTS if leave_one_out else MIN_POINTS - 1
        if len(points) < needed:
            raise FitError(f"{len(points)} control points; at least {needed} are needed")
        check_spread(points)
        self.lat0 = sum(p[2] for p in points) / len(points)
        self.lon0 = sum(p[3] for p in points) / len(points)
        self.cos0 = math.cos(math.radians(self.lat0))
        # pixels are centred on their mean too, which keeps the normal equations well conditioned
        self.x0 = sum(p[0] for p in points) / len(points)
        self.y0 = sum(p[1] for p in points) / len(points)
        rows = [(p[0] - self.x0, p[1] - self.y0, 1.0) for p in points]
        east = [self.east(p[3]) for p in points]
        north = [self.north(p[2]) for p in points]
        normal = [[sum(r[i] * r[j] for r in rows) for j in range(3)] for i in range(3)]
        self.e = solve3(normal, [sum(r[i] * t for r, t in zip(rows, east)) for i in range(3)])
        self.n = solve3(normal, [sum(r[i] * t for r, t in zip(rows, north)) for i in range(3)])
        if abs(self.e[0] * self.n[1] - self.e[1] * self.n[0]) < 1e-6:
            raise FitError("the points lie along a line on the ground: the plan would collapse onto it")
        self.residuals = [
            math.hypot(self.at(p[0], p[1])[0] - e, self.at(p[0], p[1])[1] - n) for p, e, n in zip(points, east, north)
        ]
        self.leave_one_out = self._leave_one_out(points, east, north) if leave_one_out else []

    def _leave_one_out(self, points, east, north) -> list[float]:
        """How far each point lands from where it was measured when the others alone place the plan."""
        errors = []
        for i, (p, e, n) in enumerate(zip(points, east, north)):
            try:
                others = Fit(points[:i] + points[i + 1:], leave_one_out=False)
            except FitError:
                raise FitError(f"point {i + 1} is needed to place the plan: the others lie along a line") from None
            lat, lon = others.lat_lon(p[0], p[1])
            errors.append(math.hypot(self.east(lon) - e, self.north(lat) - n))
        return errors

    def east(self, lon: float) -> float:
        return (lon - self.lon0) * METERS_PER_DEGREE * self.cos0

    def north(self, lat: float) -> float:
        return (lat - self.lat0) * METERS_PER_DEGREE

    def at(self, x: float, y: float) -> tuple[float, float]:
        dx, dy = x - self.x0, y - self.y0
        return (self.e[0] * dx + self.e[1] * dy + self.e[2], self.n[0] * dx + self.n[1] * dy + self.n[2])

    def lat_lon(self, x: float, y: float) -> tuple[float, float]:
        east, north = self.at(x, y)
        return self.lat0 + north / METERS_PER_DEGREE, self.lon0 + east / (METERS_PER_DEGREE * self.cos0)


def corners(fit: Fit, width: int, height: int) -> dict:
    def corner(x, y):
        lat, lon = fit.lat_lon(x, y)
        return {"lat": round(lat, 7), "lon": round(lon, 7)}

    return {
        "topLeft": corner(0, 0),
        "topRight": corner(width, 0),
        "bottomRight": corner(width, height),
        "bottomLeft": corner(0, height),
    }


def plan_entry(png: bytes, fit: Fit) -> dict:
    """The pack's "plan" for [png] placed by [fit]; FitError when a residual is over 5 m or the PNG won't do."""
    if len(png) > PLAN_MAX_BYTES:
        raise FitError(f"the PNG has {len(png)} bytes; at most {PLAN_MAX_BYTES}: scale it down or use fewer colours")
    width, height = png_size(png)
    worst = max(fit.residuals)
    if worst > MAX_RESIDUAL_M:
        # least squares spreads one wrong point over the others; the largest residual points at it
        raise FitError(
            f"residual {worst:.1f} m at point {fit.residuals.index(worst) + 1}, over {MAX_RESIDUAL_M:.0f} m: "
            "check that point or measure again"
        )
    worst_out = max(fit.leave_one_out)
    if worst_out > MAX_LEAVE_ONE_OUT_M:
        raise FitError(
            f"point {fit.leave_one_out.index(worst_out) + 1} lands {worst_out:.1f} m from where it was measured when the "
            f"others place the plan, over {MAX_LEAVE_ONE_OUT_M:.0f} m: check that point or measure again"
        )
    entry = {"png": base64.b64encode(png).decode("ascii")}
    entry.update(corners(fit, width, height))
    return entry


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("png", help="the site plan, a PNG")
    parser.add_argument("points", help="control points: x, y, lat, lon per line")
    parser.add_argument("--out", help="where to write the plan JSON; plan.json beside the PNG by default")
    args = parser.parse_args()
    png = Path(args.png).read_bytes()
    try:
        points = read_points(Path(args.points).read_text(encoding="utf-8"))
        fit = Fit(points)
        for i, (p, r) in enumerate(zip(points, fit.residuals), 1):
            print(f"point {i} ({p[0]:.0f}, {p[1]:.0f}): {r:.2f} m, {fit.leave_one_out[i - 1]:.2f} m without it")
        entry = plan_entry(png, fit)
    except FitError as e:
        sys.exit(f"refused: {e}")
    out = Path(args.out) if args.out else Path(args.png).with_name("plan.json")
    out.write_text(json.dumps(entry), encoding="utf-8")
    print(f"largest residual {max(fit.residuals):.2f} m; corners -> {out}")
    for name in ("topLeft", "topRight", "bottomRight", "bottomLeft"):
        print(f"  {name}: {entry[name]['lat']}, {entry[name]['lon']}")


if __name__ == "__main__":
    main()
