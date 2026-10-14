#!/usr/bin/env python3
"""Cut the festival's base map out of a Protomaps daily build, without downloading the whole planet.

    pip install pmtiles
    python tools/map_extract.py 20261012
    python tools/map_extract.py 20261012 --dry-run

Reads only the tiles inside the bounding box, zoom 0 to 15, with HTTP range requests, writes
costinesti.pmtiles and manifest.json (the pack's manifest: id, kind, build date, SHA-256, size, bbox, zooms,
attribution) into app/src/main/assets/maps/<date>/, and copies the two styles from tools/map_styles next to them.
The app checks the SHA-256 before it opens the map. Fonts and sprites are added by hand: see tools/map_assets.md.
The data is OpenStreetMap, ODbL: the map must show "© OpenStreetMap contributors".
"""

import argparse
import hashlib
import json
import math
import shutil
import sys
import urllib.request
from pathlib import Path

BBOX = (28.590, 43.920, 28.675, 44.010)  # west, south, east, north: Costinești to Tuzla, NIBIRU included
MAX_ZOOM = 15
TILES = "costinesti.pmtiles"
ROOT = Path(__file__).resolve().parent.parent
STYLES = ROOT / "tools" / "map_styles"
# what the map host sees instead of Python's default agent, which some hosts refuse
USER_AGENT = "pingup-map-tools/1.0"
OSM_ATTRIBUTION = '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>'
# the landcover layer, zooms 0 to 7, comes from ESA WorldCover (CC BY 4.0)
ESA_ATTRIBUTION = (
    '<a href="https://esa-worldcover.org/">© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021)'
    " processed by ESA WorldCover consortium</a>"
)
LANDCOVER_MAX_ZOOM = 7


def attribution(min_zoom):
    """What a pack from min_zoom up credits: OpenStreetMap always, ESA WorldCover when it holds the landcover zooms."""
    return OSM_ATTRIBUTION + (" " + ESA_ATTRIBUTION if min_zoom <= LANDCOVER_MAX_ZOOM else "")


def http_source(url, user_agent=USER_AGENT):
    cache = {}

    def get_bytes(offset, length):
        key = (offset, length)
        if key not in cache:
            request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-{offset + length - 1}", "User-Agent": user_agent})
            with urllib.request.urlopen(request, timeout=240) as response:
                if response.status != 206:
                    sys.exit(f"{url} does not answer range requests (HTTP {response.status})")
                cache[key] = response.read()
        return cache[key]

    return get_bytes


def tile_range(z, west, south, east, north):
    n = 2 ** z

    def x(lon):
        return min(n - 1, max(0, int((lon + 180) / 360 * n)))

    def y(lat):
        r = math.radians(lat)
        return min(n - 1, max(0, int((1 - math.asinh(math.tan(r)) / math.pi) / 2 * n)))

    return range(x(west), x(east) + 1), range(y(north), y(south) + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("date", help="Protomaps build date, YYYYMMDD")
    parser.add_argument("--url", help="build URL; default https://build.protomaps.com/<date>.pmtiles")
    parser.add_argument("--out", type=Path, help="default app/src/main/assets/maps/<date>")
    parser.add_argument("--dry-run", action="store_true", help="count the tiles and their size, write nothing")
    args = parser.parse_args()
    if len(args.date) != 8 or not args.date.isdigit():
        sys.exit("date must be YYYYMMDD")
    # imported here so the other map tools can reuse tile_range and http_source without the pmtiles package
    from pmtiles.reader import Reader
    from pmtiles.tile import zxy_to_tileid
    from pmtiles.writer import Writer

    url = args.url or f"https://build.protomaps.com/{args.date}.pmtiles"
    reader = Reader(http_source(url))
    header = reader.header()
    max_zoom = min(MAX_ZOOM, header["max_zoom"])

    ids = []
    for z in range(header["min_zoom"], max_zoom + 1):
        xs, ys = tile_range(z, *BBOX)
        ids += [(zxy_to_tileid(z, x, y), z, x, y) for x in xs for y in ys]
    ids.sort()

    tiles = []
    for tile_id, z, x, y in ids:
        data = reader.get(z, x, y)
        if data:
            tiles.append((tile_id, data))
    size = sum(len(data) for _, data in tiles)
    print(f"{len(tiles)} tiles, {size / 1e6:.2f} MB, zoom {header['min_zoom']}-{max_zoom}")
    if args.dry_run:
        return

    out = args.out or ROOT / "app" / "src" / "main" / "assets" / "maps" / args.date
    out.mkdir(parents=True, exist_ok=True)
    target = out / TILES
    west, south, east, north = BBOX
    with open(target, "wb") as f:
        writer = Writer(f)
        for tile_id, data in tiles:
            writer.write_tile(tile_id, data)
        writer.finalize(
            {
                "tile_type": header["tile_type"],
                "tile_compression": header["tile_compression"],
                "min_zoom": header["min_zoom"],
                "max_zoom": max_zoom,
                "min_lon_e7": int(west * 1e7),
                "min_lat_e7": int(south * 1e7),
                "max_lon_e7": int(east * 1e7),
                "max_lat_e7": int(north * 1e7),
                "center_zoom": max_zoom,
                "center_lon_e7": int((west + east) / 2 * 1e7),
                "center_lat_e7": int((south + north) / 2 * 1e7),
            },
            reader.metadata(),
        )

    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    # the fields the old app reads come first; the rest make it a PackManifest (core/map/MapPacks.kt)
    manifest = {
        "buildDate": args.date, "sha256": digest, "bbox": list(BBOX), "maxzoom": max_zoom, "file": TILES,
        "id": "costinesti", "kind": "EVENT", "bytes": target.stat().st_size, "minzoom": header["min_zoom"],
        "attribution": attribution(header["min_zoom"]), "label": "Costinești",
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for style in ("style-light.json", "style-dark.json"):
        shutil.copyfile(STYLES / style, out / style)
    print(f"wrote {out} (sha256 {digest})")


if __name__ == "__main__":
    main()
