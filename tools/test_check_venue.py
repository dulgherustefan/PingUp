"""Tests for check_venue.py: python3 tools/test_check_venue.py"""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_venue  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


def square(zone_id, lat, lon, size=1.0):
    return {
        "id": zone_id, "name": zone_id.title(), "color": "#336699",
        "polygon": [{"lat": lat, "lon": lon}, {"lat": lat, "lon": lon + size},
                    {"lat": lat + size, "lon": lon + size}, {"lat": lat + size, "lon": lon}],
    }


def venue(*zones, meeting=None):
    v = {"name": "Test", "bounds": {"minLat": 0, "minLon": 0, "maxLat": 10, "maxLon": 10}, "zones": list(zones)}
    if meeting:
        v["meetingPoint"] = meeting
    return v


class CheckVenueTest(unittest.TestCase):
    def test_the_bundled_venue_passes(self):
        data = json.loads((ROOT / "app/src/main/assets/venue.json").read_text(encoding="utf-8"))
        self.assertEqual([], check_venue.problems(data))

    def test_zones_sharing_a_border_are_fine(self):
        self.assertEqual([], check_venue.problems(venue(square("a", 1, 1), square("b", 1, 2))))

    def test_overlapping_zones_are_reported(self):
        found = check_venue.problems(venue(square("a", 1, 1), square("b", 1.5, 1.5)))
        self.assertTrue(any("overlap" in p for p in found), found)

    def test_a_zone_inside_another_is_reported(self):
        found = check_venue.problems(venue(square("big", 1, 1, 4), square("small", 2, 2, 1)))
        self.assertTrue(any("overlap" in p for p in found), found)

    def test_the_same_zone_twice_is_an_overlap(self):
        found = check_venue.problems(venue(square("a", 1, 1), square("b", 1, 1)))
        self.assertTrue(any("overlap" in p for p in found), found)

    def test_a_zone_inside_another_along_its_edges_is_reported(self):
        # the left half of a zone, left behind after splitting it
        half = square("half", 1, 1)
        half["polygon"] = [{"lat": 1, "lon": 1}, {"lat": 1, "lon": 2}, {"lat": 3, "lon": 2}, {"lat": 3, "lon": 1}]
        found = check_venue.problems(venue(square("whole", 1, 1, 2), half))
        self.assertTrue(any("overlap" in p for p in found), found)

    def test_points_outside_the_bounds_are_reported(self):
        found = check_venue.problems(venue(square("a", 9.5, 9.5)))
        self.assertTrue(any("outside bounds" in p for p in found), found)

    def test_duplicate_and_too_long_ids_are_reported(self):
        found = check_venue.problems(venue(square("a", 1, 1), square("a", 5, 5), square("x" * 33, 7, 1)))
        self.assertTrue(any("duplicate id" in p for p in found), found)
        self.assertTrue(any("32 bytes" in p for p in found), found)

    def test_a_self_crossing_outline_is_reported(self):
        bow = square("bow", 1, 1)
        bow["polygon"][1], bow["polygon"][2] = bow["polygon"][2], bow["polygon"][1]
        found = check_venue.problems(venue(bow))
        self.assertTrue(any("crosses itself" in p for p in found), found)

    def test_meeting_point_outside_is_reported(self):
        found = check_venue.problems(venue(square("a", 1, 1), meeting={"lat": 20, "lon": 1}))
        self.assertTrue(any("meeting point" in p for p in found), found)

    def test_points_of_interest_need_a_type_and_a_place_inside_the_bounds(self):
        v = venue(square("a", 1, 1))
        v["pois"] = [{"type": "wc", "lat": 5, "lon": 5}, {"type": "", "lat": 5, "lon": 6}, {"type": "dea", "lat": 20, "lon": 5}]
        found = check_venue.problems(v)
        self.assertTrue(any("point 2" in p and "type" in p for p in found), found)
        self.assertTrue(any("point 3" in p and "outside bounds" in p for p in found), found)
        self.assertFalse(any("point 1" in p for p in found), found)

    def test_landmark_numbers_fit_in_a_byte_and_are_not_repeated(self):
        v = venue(square("a", 1, 1))
        v["pois"] = [{"type": "info", "lat": 5, "lon": 5, "number": 4}, {"type": "exit", "lat": 6, "lon": 6, "number": 4},
                     {"type": "water", "lat": 7, "lon": 7, "number": 256}]
        found = check_venue.problems(v)
        self.assertTrue(any("number 4" in p for p in found), found)
        self.assertTrue(any("1 to 255" in p for p in found), found)

    def test_a_path_needs_two_points_inside_the_bounds(self):
        v = venue(square("a", 1, 1))
        v["paths"] = [[{"lat": 1, "lon": 1}, {"lat": 2, "lon": 2}], [{"lat": 1, "lon": 1}], [{"lat": 1, "lon": 1}, {"lat": 11, "lon": 1}]]
        found = check_venue.problems(v)
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("path 2" in p for p in found), found)
        self.assertTrue(any("path 3" in p and "outside bounds" in p for p in found), found)


if __name__ == "__main__":
    unittest.main()
