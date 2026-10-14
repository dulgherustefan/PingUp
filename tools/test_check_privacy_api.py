"""Tests for check_privacy_api.py and the privacy manifest it reads: python3 tools/test_check_privacy_api.py"""

import plistlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_privacy_api  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


class CheckPrivacyApiTest(unittest.TestCase):
    def test_an_imported_symbol_names_its_category(self):
        found = check_privacy_api.used({"_stat", "_free", "_mach_absolute_time"}, b"")
        self.assertEqual(["_stat"], found["NSPrivacyAccessedAPICategoryFileTimestamp"])
        self.assertEqual(["_mach_absolute_time"], found["NSPrivacyAccessedAPICategorySystemBootTime"])
        self.assertNotIn("NSPrivacyAccessedAPICategoryDiskSpace", found)

    def test_a_selector_is_found_in_the_binary_but_not_inside_a_longer_name(self):
        self.assertIn("NSPrivacyAccessedAPICategorySystemBootTime", check_privacy_api.used(set(), b"x\0systemUptime\0y"))
        self.assertEqual({}, check_privacy_api.used(set(), b"\0systemUptimeLabel\0"))

    def test_only_what_the_manifest_lacks_is_missing(self):
        found = check_privacy_api.used({"_stat", "_statfs"}, b"")
        have = {"NSPrivacyAccessedAPICategoryFileTimestamp"}
        self.assertEqual({"NSPrivacyAccessedAPICategoryDiskSpace": ["_statfs"]}, check_privacy_api.missing(found, have))

    def test_the_apps_manifest_declares_what_it_uses_today(self):
        with open(ROOT / "iosApp" / "PingUp" / "PrivacyInfo.xcprivacy", "rb") as f:
            have = check_privacy_api.declared(plistlib.load(f))
        self.assertTrue({
            "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPICategoryFileTimestamp",
            "NSPrivacyAccessedAPICategorySystemBootTime",
        } <= have)


if __name__ == "__main__":
    unittest.main()
