#!/usr/bin/env python3
"""Checks that the iPhone app declares a reason for every "required reason" API its binary uses.

  python3 tools/check_privacy_api.py build/.../PingUp.app/PingUp iosApp/PingUp/PrivacyInfo.xcprivacy

Apple rejects an upload (ITMS-91053) whose binary calls one of these APIs without a reason in the privacy manifest.
The Kotlin framework is linked statically into the app, so the app's own binary holds what Kotlin, Compose and Okio
use. C functions and constants show in the symbols the binary imports (nm -u); Objective-C methods only as selector
names, which are looked for in the binary itself. Apple's list:
https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
"""

import plistlib
import subprocess
import sys

SYMBOLS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": [
        "_stat", "_fstat", "_fstatat", "_lstat", "_getattrlist", "_getattrlistbulk", "_fgetattrlist", "_getattrlistat",
        "_NSFileCreationDate", "_NSFileModificationDate", "_NSURLContentModificationDateKey", "_NSURLCreationDateKey",
    ],
    "NSPrivacyAccessedAPICategorySystemBootTime": ["_mach_absolute_time"],
    "NSPrivacyAccessedAPICategoryDiskSpace": [
        "_statfs", "_statvfs", "_fstatfs", "_fstatvfs", "_NSFileSystemFreeSize", "_NSFileSystemSize",
        "_NSURLVolumeAvailableCapacityKey", "_NSURLVolumeAvailableCapacityForImportantUsageKey",
        "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "_NSURLVolumeTotalCapacityKey",
    ],
    "NSPrivacyAccessedAPICategoryUserDefaults": ["_OBJC_CLASS_$_NSUserDefaults"],
}

SELECTORS = {
    "NSPrivacyAccessedAPICategorySystemBootTime": [b"systemUptime"],
    "NSPrivacyAccessedAPICategoryActiveKeyboards": [b"activeInputModes"],
}


def used(imports, binary):
    """The categories the binary uses, each with what gave it away."""
    found = {}
    for category, names in SYMBOLS.items():
        hits = [name for name in names if name in imports]
        if hits:
            found[category] = hits
    for category, selectors in SELECTORS.items():
        hits = [s.decode() for s in selectors if b"\0" + s + b"\0" in binary]
        if hits:
            found.setdefault(category, []).extend(hits)
    return found


def declared(manifest):
    types = manifest.get("NSPrivacyAccessedAPITypes", [])
    return {t["NSPrivacyAccessedAPIType"] for t in types if t.get("NSPrivacyAccessedAPITypeReasons")}


def missing(found, have):
    return {category: hits for category, hits in found.items() if category not in have}


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    binary_path, manifest_path = sys.argv[1], sys.argv[2]
    imports = set(subprocess.run(["nm", "-u", binary_path], check=True, capture_output=True, text=True).stdout.split())
    with open(binary_path, "rb") as f:
        binary = f.read()
    with open(manifest_path, "rb") as f:
        have = declared(plistlib.load(f))
    found = used(imports, binary)
    for category, hits in sorted(found.items()):
        print(f"{category}: {', '.join(hits)}" + ("" if category in have else "  <- no reason declared"))
    for category in sorted(have - set(found)):
        print(f"{category}: declared, not seen in the binary (kept: a library may call it in a way nm doesn't show)")
    gaps = missing(found, have)
    if gaps:
        sys.exit("add a reason to the privacy manifest for: " + ", ".join(sorted(gaps)))


if __name__ == "__main__":
    main()
