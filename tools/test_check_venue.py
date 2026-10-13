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

    def site(self):
        """Two zones, a fence along lat 5 with a gate at exit 1, exits 1 and 2, and a valid plan."""
        v = venue(square("a", 1, 1), square("b", 1, 3))
        v["pois"] = [{"type": "exit", "lat": 5, "lon": 2, "number": 1}, {"type": "exit", "lat": 0.5, "lon": 4, "number": 2},
                     {"type": "info", "lat": 3, "lon": 3, "number": 3}]
        v["barriers"] = [[{"lat": 5, "lon": 0.5}, {"lat": 5, "lon": 9.5}]]
        v["gates"] = [{"lat": 5, "lon": 2, "name": "Poarta", "number": 1}]
        v["evacuation"] = [{"id": 1, "name": "A", "exits": {"a": {"main": 1, "alternative": 2}, "b": {"main": 2}}}]
        return v

    def test_the_site_data_passes(self):
        v = self.site()
        v["water"] = [[{"lat": 6, "lon": 6}, {"lat": 12, "lon": 6}, {"lat": 12, "lon": 12}, {"lat": 6, "lon": 12}]]
        v["blocked"] = [{"access": "staff", "polygon": [{"lat": 1.2, "lon": 1.2}, {"lat": 1.2, "lon": 1.5}, {"lat": 1.5, "lon": 1.5}]}]
        v["meetingLandmarks"] = [3]
        self.assertEqual([], check_venue.problems(v))

    def test_water_over_a_zone_or_a_point_is_reported(self):
        v = self.site()
        v["water"] = [[{"lat": 0, "lon": 0}, {"lat": 0, "lon": 2.5}, {"lat": 4, "lon": 2.5}, {"lat": 4, "lon": 0}]]
        found = check_venue.problems(v)
        self.assertTrue(any("water 1 covers zone a" in p for p in found), found)
        v["water"] = [[{"lat": 2.8, "lon": 2.8}, {"lat": 2.8, "lon": 3.2}, {"lat": 3.2, "lon": 3.2}, {"lat": 3.2, "lon": 2.8}]]
        v["zones"] = [square("a", 1, 1)]
        v["evacuation"] = []
        found = check_venue.problems(v)
        self.assertTrue(any("covers point" in p for p in found), found)

    def test_a_gate_off_every_fence_or_naming_no_point_is_reported(self):
        v = self.site()
        v["gates"] = [{"lat": 6, "lon": 2, "name": "Departe"}, {"lat": 5, "lon": 3, "name": "Fără punct", "number": 9}]
        found = check_venue.problems(v)
        self.assertTrue(any("Departe" in p and "not on a barrier" in p for p in found), found)
        self.assertTrue(any("Fără punct" in p and "number 9" in p for p in found), found)

    def test_a_blocked_area_needs_a_known_access_and_a_polygon(self):
        v = self.site()
        v["blocked"] = [{"access": "vip", "polygon": [{"lat": 1, "lon": 1}, {"lat": 1, "lon": 2}, {"lat": 2, "lon": 2}]},
                        {"access": "none", "polygon": [{"lat": 1, "lon": 1}]}]
        found = check_venue.problems(v)
        self.assertTrue(any("blocked area 1" in p and "access" in p for p in found), found)
        self.assertTrue(any("blocked area 2" in p and "at least 3" in p for p in found), found)

    def test_meeting_landmarks_are_numbers_of_points(self):
        v = self.site()
        v["meetingLandmarks"] = [3, 7]
        found = check_venue.problems(v)
        self.assertEqual(["meeting landmark 7 is not the number of a point"], found)

    def test_an_evacuation_plan_covers_every_zone_with_two_different_exits(self):
        v = self.site()
        v["evacuation"] = [
            {"id": 1, "name": "A", "exits": {"a": {"main": 1, "alternative": 1}, "x": {"main": 3}}},
            {"id": 1, "name": "B", "exits": {"a": {"main": 2}, "b": {"main": 2}}},
            {"id": 300, "name": "C", "exits": {"a": {"main": 1}, "b": {"main": 2}}},
        ]
        found = check_venue.problems(v)
        self.assertTrue(any("plan 1, zone a" in p and "main exit again" in p for p in found), found)
        self.assertTrue(any("zone b has no exit" in p for p in found), found)
        self.assertTrue(any("x is not a zone" in p for p in found), found)
        self.assertTrue(any("zone x: main exit 3 is not the number of an exit point" in p for p in found), found)
        self.assertTrue(any("same id" in p for p in found), found)
        self.assertTrue(any("single exit" in p for p in found), found)
        self.assertTrue(any("plan 300" in p and "1 to 255" in p for p in found), found)


if __name__ == "__main__":
    unittest.main()
