"""Tests for check_pack.py: python3 tools/test_check_pack.py"""

import base64
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_pack  # noqa: E402
import plan_corners  # noqa: E402
from test_plan_corners import png  # noqa: E402

VENUE = {"zones": [{"id": "main"}, {"id": "second"}]}


def item(**kw):
    base = {"stage": "main", "title": "Headliner", "start": 1_786_000_000, "end": 1_786_005_400}
    base.update(kw)
    return base


def plan(**kw):
    base = {
        "png": base64.b64encode(png(8, 6)).decode(),
        "topLeft": {"lat": 44.0, "lon": 28.0}, "topRight": {"lat": 44.0, "lon": 28.1},
        "bottomRight": {"lat": 43.9, "lon": 28.1}, "bottomLeft": {"lat": 43.9, "lon": 28.0},
    }
    base.update(kw)
    return base


class ScheduleTest(unittest.TestCase):
    def test_a_good_schedule_passes(self):
        self.assertEqual([], check_pack.schedule_problems([item(), item(stage="second", start=0, end=43_200)], VENUE))
        self.assertEqual([], check_pack.schedule_problems([], VENUE))

    def test_each_rule(self):
        cases = {
            "stage": item(stage="nowhere"),
            "title": item(title="  "),
            "80 characters": item(title="x" * 81),
            "before it starts": item(end=1_786_000_000),
            "12 hours": item(end=1_786_000_000 + 12 * 3600 + 1),
            "whole seconds": item(start=1.5),
            "start and end": item(start=True),
        }
        for word, bad in cases.items():
            found = check_pack.schedule_problems([bad], VENUE)
            self.assertTrue(found and word in found[0], f"{word}: {found}")
        self.assertEqual([], check_pack.schedule_problems([item(title="x" * 80)], VENUE))
        self.assertEqual([], check_pack.schedule_problems([item(end=1_786_000_000 + 12 * 3600)], VENUE))

    def test_too_many_sets(self):
        self.assertTrue(check_pack.schedule_problems([item()] * 1001, VENUE))
        self.assertEqual([], check_pack.schedule_problems([item()] * 1000, VENUE))
        self.assertTrue(check_pack.schedule_problems({"stage": "main"}, VENUE))


class PlanTest(unittest.TestCase):
    def test_a_good_plan_passes(self):
        self.assertEqual([], check_pack.plan_problems(plan()))

    def test_each_rule(self):
        self.assertTrue(check_pack.plan_problems(plan(png="not base64!")))
        self.assertTrue(check_pack.plan_problems(plan(png=base64.b64encode(b"GIF89a" + bytes(30)).decode())))
        big = base64.b64encode(png(8, 6) + bytes(plan_corners.PLAN_MAX_BYTES)).decode()
        self.assertTrue(check_pack.plan_problems(plan(png=big)))
        self.assertTrue(check_pack.plan_problems(plan(topLeft={"lat": 44.0})))
        self.assertTrue(check_pack.plan_problems(plan(topLeft={"lat": float("nan"), "lon": 1.0})))
        self.assertTrue(check_pack.plan_problems(plan(topLeft={"lat": 95, "lon": 1.0})))
        same = plan(topRight={"lat": 44.0, "lon": 28.0})
        self.assertIn("different", check_pack.plan_problems(same)[0])
        self.assertTrue(check_pack.plan_problems([]))


if __name__ == "__main__":
    unittest.main()
