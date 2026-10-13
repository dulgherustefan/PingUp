"""Tests for plan_corners.py: python3 tools/test_plan_corners.py"""

import base64
import math
import struct
import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import plan_corners  # noqa: E402
from plan_corners import METERS_PER_DEGREE, Fit, FitError  # noqa: E402

LAT0, LON0 = 43.9497, 28.6355
COS0 = math.cos(math.radians(LAT0))


def png(width, height):
    """A real, tiny PNG: grey pixels, one filter byte per row."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    raw = b"".join(b"\x00" + b"\x80" * width for _ in range(height))
    return (plan_corners.PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def ground(x, y, scale=0.5, turn=20.0, skew=0.0):
    """Where pixel (x, y) of a plan lies: [scale] m a pixel, turned [turn] degrees, image rows running south."""
    t = math.radians(turn)
    east = scale * (x * math.cos(t) + y * (math.sin(t) + skew))
    north = scale * (x * math.sin(t) - y * math.cos(t))
    return LAT0 + north / METERS_PER_DEGREE, LON0 + east / (METERS_PER_DEGREE * COS0)


def offset(lat, lon, east_m, north_m):
    return lat + north_m / METERS_PER_DEGREE, lon + east_m / (METERS_PER_DEGREE * COS0)


def meters(a, b):
    return math.hypot((a[0] - b[0]) * METERS_PER_DEGREE, (a[1] - b[1]) * METERS_PER_DEGREE * COS0)


PIXELS = [(40, 30), (760, 50), (740, 560), (60, 580), (400, 300)]


def points(**kw):
    return [(x, y, *ground(x, y, **kw)) for x, y in PIXELS]


class PlanCornersTest(unittest.TestCase):
    def test_exact_points_give_the_plans_corners(self):
        fit = Fit(points())
        self.assertLess(max(fit.residuals), 0.01)
        entry = plan_corners.plan_entry(png(800, 600), fit)
        for name, (x, y) in {"topLeft": (0, 0), "topRight": (800, 0), "bottomRight": (800, 600), "bottomLeft": (0, 600)}.items():
            self.assertLess(meters((entry[name]["lat"], entry[name]["lon"]), ground(x, y)), 0.05, name)
        self.assertEqual(png(800, 600), base64.b64decode(entry["png"]))

    def test_a_sheared_plan_still_fits(self):
        fit = Fit(points(scale=0.3, turn=-35, skew=0.2))
        self.assertLess(max(fit.residuals), 0.01)
        self.assertLess(meters(fit.lat_lon(800, 600), ground(800, 600, scale=0.3, turn=-35, skew=0.2)), 0.05)

    def test_small_errors_pass_with_their_residuals(self):
        noisy = points()
        shifts = [(1.0, -0.5), (-0.8, 0.6), (0.4, 1.1), (-1.0, -0.7), (0.3, 0.2)]
        noisy = [(x, y, *offset(lat, lon, e, n)) for (x, y, lat, lon), (e, n) in zip(noisy, shifts)]
        fit = Fit(noisy)
        self.assertTrue(0.1 < max(fit.residuals) < plan_corners.MAX_RESIDUAL_M)
        plan_corners.plan_entry(png(8, 6), fit)

    def test_a_residual_over_five_metres_is_refused(self):
        bad = points()
        x, y, lat, lon = bad[2]
        bad[2] = (x, y, *offset(lat, lon, 25.0, 0.0))
        fit = Fit(bad)
        self.assertGreater(max(fit.residuals), plan_corners.MAX_RESIDUAL_M)
        with self.assertRaises(FitError) as caught:
            plan_corners.plan_entry(png(8, 6), fit)
        self.assertIn("at point 3,", str(caught.exception))

    def test_too_few_or_lined_up_points_are_refused(self):
        with self.assertRaises(FitError):
            Fit(points()[:3])
        line = [(x, 2 * x, *ground(x, 2 * x)) for x in (0, 100, 200, 300)]
        with self.assertRaises(FitError):
            Fit(line)

    def test_five_points_are_the_least(self):
        with self.assertRaises(FitError):
            Fit(points()[:4])

    def test_points_near_one_line_are_refused(self):
        near_line = [(x, 0.03 * x + (7 if i % 2 else -7), 0, 0) for i, x in enumerate((0, 150, 300, 450, 600, 750))]
        near_line = [(x, y, *ground(x, y)) for x, y, _, _ in near_line]
        with self.assertRaises(FitError) as caught:
            Fit(near_line)
        self.assertIn("one line", str(caught.exception))
        # spread over the plan is fine
        Fit(points())

    def test_points_on_one_line_of_the_ground_are_refused(self):
        east = [(x, y, *offset(LAT0, LON0, 0.5 * (x + y), 0.0)) for x, y in PIXELS]
        with self.assertRaises(FitError):
            Fit(east)

    def test_one_wrong_point_is_caught_even_where_the_fit_bends_to_it(self):
        for wrong in range(len(PIXELS)):
            bad = points()
            x, y, lat, lon = bad[wrong]
            bad[wrong] = (x, y, *offset(lat, lon, 0.0, 15.0))
            fit = Fit(bad)
            self.assertAlmostEqual(15.0, fit.leave_one_out[wrong], delta=0.01)
            with self.assertRaises(FitError, msg=f"point {wrong + 1}"):
                plan_corners.plan_entry(png(8, 6), fit)

    def test_not_a_number_is_refused(self):
        for broken in ("nan, 2, 43.9, 28.6", "1, inf, 43.9, 28.6", "1, 2, nan, 28.6", "1, 2, 43.9, -inf"):
            with self.assertRaises(FitError, msg=broken):
                plan_corners.read_points(broken)
        bad = points()
        bad[1] = (math.nan, *bad[1][1:])
        with self.assertRaises(FitError):
            Fit(bad)

    def test_only_a_small_enough_png_goes_in(self):
        fit = Fit(points())
        self.assertEqual((800, 600), plan_corners.png_size(png(800, 600)))
        with self.assertRaises(FitError):
            plan_corners.png_size(b"GIF89a" + bytes(30))
        too_big = png(8, 6) + bytes(plan_corners.PLAN_MAX_BYTES)
        with self.assertRaises(FitError):
            plan_corners.plan_entry(too_big, fit)

    def test_points_file(self):
        text = "# x, y, lat, lon\n412, 96, 43.95091, 28.63402\n\n1630;120;43.95085;28.63761  # gate\n"
        self.assertEqual([(412, 96, 43.95091, 28.63402), (1630, 120, 43.95085, 28.63761)], plan_corners.read_points(text))
        for broken in ("1, 2, 3", "a, 2, 43.9, 28.6", "1, 2, 95, 28.6"):
            with self.assertRaises(FitError, msg=broken):
                plan_corners.read_points(broken)


if __name__ == "__main__":
    unittest.main()
