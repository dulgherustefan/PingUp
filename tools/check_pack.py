#!/usr/bin/env python3
"""Checks the schedule and the site plan before they are signed into an event pack.

  python3 tools/check_pack.py venue.json [--schedule schedule.json] [--plan plan.json]

A signed pack can not be fixed on the phones, and a phone throws away a pack whose schedule or plan it can not read
(core/event/EventPack.kt), so gen_staff_keys.py pack runs these checks first.

The schedule is a list of {"stage": "<zone id>", "title": "...", "start": <unix seconds>, "end": <unix seconds>}:
at most 1000 sets, each on a stage that is a zone of the venue, with a title of 1 to 80 characters and an end after
its start by at most 12 hours. The plan is what plan_corners.py writes: a PNG of at most 2 MiB, in base64, and the
four distinct corners.
"""

import argparse
import base64
import binascii
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import plan_corners  # noqa: E402

SCHEDULE_MAX_ITEMS = 1000
TITLE_CHARS = 80
SET_MAX_S = 12 * 3600
CORNERS = ("topLeft", "topRight", "bottomRight", "bottomLeft")


def _seconds(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def schedule_problems(schedule, venue: dict) -> list[str]:
    if not isinstance(schedule, list):
        return ["the schedule is not a list"]
    problems = []
    if len(schedule) > SCHEDULE_MAX_ITEMS:
        problems.append(f"{len(schedule)} sets; at most {SCHEDULE_MAX_ITEMS}")
    zones = {z.get("id") for z in venue.get("zones", []) if isinstance(z, dict)}
    for number, item in enumerate(schedule[:SCHEDULE_MAX_ITEMS], 1):
        if not isinstance(item, dict):
            problems.append(f"set {number}: not an object")
            continue
        stage, title, start, end = (item.get(k) for k in ("stage", "title", "start", "end"))
        if not isinstance(stage, str) or stage not in zones:
            problems.append(f"set {number}: stage {stage!r} is not a zone of the venue")
        if not isinstance(title, str) or not title.strip():
            problems.append(f"set {number}: no title")
        # the app counts UTF-16 units, as Kotlin's String.length does
        elif len(title.encode("utf-16-le")) // 2 > TITLE_CHARS:
            problems.append(f"set {number}: the title has more than {TITLE_CHARS} characters")
        if not _seconds(start) or not _seconds(end):
            problems.append(f"set {number}: start and end must be whole seconds since the Unix epoch")
        elif end <= start:
            problems.append(f"set {number}: it ends before it starts")
        elif end - start > SET_MAX_S:
            problems.append(f"set {number}: it lasts more than {SET_MAX_S // 3600} hours")
    return problems


def plan_problems(plan) -> list[str]:
    if not isinstance(plan, dict):
        return ["the plan is not an object"]
    problems = []
    text = plan.get("png")
    if not isinstance(text, str):
        problems.append("the plan has no png")
    elif len(text) > (plan_corners.PLAN_MAX_BYTES + 2) // 3 * 4:
        problems.append(f"the PNG is over {plan_corners.PLAN_MAX_BYTES} bytes")
    else:
        try:
            data = base64.b64decode(text, validate=True)
            if len(data) > plan_corners.PLAN_MAX_BYTES:
                problems.append(f"the PNG has {len(data)} bytes; at most {plan_corners.PLAN_MAX_BYTES}")
            plan_corners.png_size(data)
        except (binascii.Error, ValueError):
            problems.append("the png is not base64")
        except plan_corners.FitError as e:
            problems.append(f"the png: {e}")
    corners = []
    for name in CORNERS:
        c = plan.get(name)
        lat = c.get("lat") if isinstance(c, dict) else None
        lon = c.get("lon") if isinstance(c, dict) else None
        ok = all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (lat, lon))
        if not ok or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            problems.append(f"{name}: needs a latitude and a longitude")
        else:
            corners.append((lat, lon))
    if len(corners) == len(CORNERS) and len(set(corners)) != len(CORNERS):
        problems.append("the four corners must be four different points")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("venue")
    parser.add_argument("--schedule")
    parser.add_argument("--plan")
    args = parser.parse_args()
    found = []
    if args.schedule:
        venue = json.loads(Path(args.venue).read_text(encoding="utf-8"))
        schedule = json.loads(Path(args.schedule).read_text(encoding="utf-8"))
        found += [f"{args.schedule}: {p}" for p in schedule_problems(schedule, venue)]
    if args.plan:
        found += [f"{args.plan}: {p}" for p in plan_problems(json.loads(Path(args.plan).read_text(encoding="utf-8")))]
    if found:
        sys.exit("\n".join(found))
    print("ok")


if __name__ == "__main__":
    main()
