#!/usr/bin/env python3
"""Build the signed catalog of the map packs the app offers to download ("Descarcă o țară sau regiune").

    pip install pynacl
    python tools/map_catalog.py keygen
    python tools/map_catalog.py build packs.json --base-url https://maps.example.org/packs/
    python tools/map_catalog.py verify tools/out/maps_catalog.json --public <hex>

packs.json lists the packs, PMTiles files cut with the app's schema (map_overview.py --bbox, or pmtiles extract),
with paths relative to packs.json:

    [{"file": "ro-constanta/ro-constanta.pmtiles", "id": "ro-constanta", "label": "Constanța",
      "outline": "outlines/constanta.geojson"}]

id, label, buildDate and attribution default to the manifest.json beside the file. The bbox and the zooms come from
the file's header, sha256 and bytes from its bytes, the outline from the largest polygon of a GeoJSON file, simplified
to at most --max-points points (the bbox when there is none). Each pack goes on the host as <id>-<buildDate>.pmtiles
under --base-url, a name that never changes; build prints where each file goes. Nothing here touches the network.

The catalog is {"format": "pingup-maps", "version": 1, "body": "<JSON text>", "signature": "<hex>"}, the signature
Ed25519 over "SP-MAPS-v1" followed by the body's UTF-8 bytes, as gen_staff_keys.py signs event packs. The body is
{"generated": "yyyyMMdd", "packs": [{"id", "label", "url", "sha256", "bytes", "bbox", "outline", "minzoom", "maxzoom",
"buildDate", "attribution"}]}: bbox west, south, east, north; outline a list of {"lat", "lon"}, the first point not
repeated. keygen keeps the seed in tools/out/maps_catalog_key.json (never in git: keep it offline, like the staff
root) and prints the public key the app checks the catalog with.
"""

import argparse
import hashlib
import json
import math
import os
import re
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from map_extract import OSM_ATTRIBUTION, ROOT  # noqa: E402
from map_overview import Archive, file_source  # noqa: E402

FORMAT = "pingup-maps"
DOMAIN = b"SP-MAPS-v1"
KEY = ROOT / "tools" / "out" / "maps_catalog_key.json"
OUT = ROOT / "tools" / "out" / "maps_catalog.json"
PACK_ID = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
MAX_POINTS = 200
TOLERANCE = 0.005  # degrees, about 500 m: half a zoom-15 tile


def rings(geojson):
    """The exterior rings of every polygon in a GeoJSON object, as (lon, lat) lists."""
    kind = geojson.get("type")
    if kind == "FeatureCollection":
        return [r for feature in geojson.get("features", []) for r in rings(feature)]
    if kind == "Feature":
        return rings(geojson.get("geometry") or {})
    if kind == "Polygon":
        return [[(float(p[0]), float(p[1])) for p in geojson["coordinates"][0]]]
    if kind == "MultiPolygon":
        return [[(float(p[0]), float(p[1])) for p in polygon[0]] for polygon in geojson["coordinates"]]
    return []


def area(ring):
    return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:] + ring[:1]))) / 2


def largest_ring(geojson):
    """The largest polygon's exterior ring, without the closing point; islands and holes are left out."""
    found = rings(geojson)
    if not found:
        raise ValueError("no polygon in the outline")
    ring = max(found, key=area)
    return ring[:-1] if len(ring) > 1 and ring[0] == ring[-1] else ring


def _distance(p, a, b, scale):
    """From p to the segment a-b, in degrees of latitude, with longitudes shrunk by cos(latitude)."""
    px, py, ax, ay, bx, by = p[0] * scale, p[1], a[0] * scale, a[1], b[0] * scale, b[1]
    dx, dy = bx - ax, by - ay
    length = dx * dx + dy * dy
    t = 0.0 if length == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def _douglas_peucker(points, tolerance, scale):
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        far, index = 0.0, None
        for i in range(first + 1, last):
            d = _distance(points[i], points[first], points[last], scale)
            if d > far:
                far, index = d, i
        if index is not None and far > tolerance:
            keep[index] = True
            stack += [(first, index), (index, last)]
    return [p for p, k in zip(points, keep) if k]


def simplify(ring, tolerance):
    """Douglas-Peucker on a ring: split at the point farthest from the first, so both halves have two ends."""
    if len(ring) <= 4:
        return list(ring)
    scale = math.cos(math.radians(sum(p[1] for p in ring) / len(ring)))
    start = ring[0]
    split = max(range(len(ring)), key=lambda i: math.hypot((ring[i][0] - start[0]) * scale, ring[i][1] - start[1]))
    first = _douglas_peucker(ring[:split + 1], tolerance, scale)
    second = _douglas_peucker(ring[split:] + [start], tolerance, scale)
    return first[:-1] + second[:-1]


def outline(ring, max_points=MAX_POINTS, tolerance=TOLERANCE):
    """The ring with at most max_points points, simplified as little as that allows."""
    while True:
        simple = simplify(ring, tolerance)
        if len(simple) <= max_points:
            return simple
        tolerance *= 2


def box(bbox):
    west, south, east, north = bbox
    return [(west, south), (east, south), (east, north), (west, north)]


def pack(entry, base, base_url, max_points=MAX_POINTS):
    """One catalog entry from one packs.json item; base is the folder packs.json is in."""
    path = base / entry["file"]
    beside = path.parent / "manifest.json"
    manifest = json.loads(beside.read_text(encoding="utf-8")) if beside.is_file() else {}
    pack_id = entry.get("id") or manifest.get("id")
    if not pack_id or not PACK_ID.fullmatch(pack_id):
        raise ValueError(f"{path}: the id must be lowercase letters, digits and dashes")
    built = entry.get("buildDate") or manifest.get("buildDate")
    if not built or len(built) != 8 or not built.isdigit():
        raise ValueError(f"{path}: no buildDate (yyyyMMdd) in packs.json or a manifest.json beside it")
    header = Archive(file_source(path)).header
    bbox = [round(header[k] / 1e7, 7) for k in ("min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7")]
    ring = largest_ring(json.loads((base / entry["outline"]).read_text(encoding="utf-8"))) if entry.get("outline") else box(bbox)
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return {
        "id": pack_id,
        "label": entry.get("label") or manifest.get("label") or pack_id,
        "url": f"{base_url}{pack_id}-{built}.pmtiles",
        "sha256": digest.hexdigest(),
        "bytes": path.stat().st_size,
        "bbox": bbox,
        "outline": [{"lat": round(lat, 5), "lon": round(lon, 5)} for lon, lat in outline(ring, max_points)],
        "minzoom": header["min_zoom"],
        "maxzoom": header["max_zoom"],
        "buildDate": built,
        "attribution": entry.get("attribution") or manifest.get("attribution") or OSM_ATTRIBUTION,
    }


def body(entries, generated):
    ids = [e["id"] for e in entries]
    if len(set(ids)) != len(ids):
        raise ValueError("two packs have the same id")
    return json.dumps({"generated": generated, "packs": entries}, ensure_ascii=False, separators=(",", ":"))


def sign(text, seed):
    """The catalog around text, signed with the Ed25519 key of seed, as gen_staff_keys.py signs."""
    from gen_staff_keys import sign_pair
    from nacl import bindings

    _, secret = sign_pair(seed)
    signature = bindings.crypto_sign(DOMAIN + text.encode("utf-8"), secret)[:64]
    return {"format": FORMAT, "version": 1, "body": text, "signature": signature.hex()}


def verify(catalog, public):
    """Whether the catalog is ours and whole: what the phone checks before it trusts a pack's URL and hash."""
    from nacl import bindings, exceptions

    if catalog.get("format") != FORMAT or catalog.get("version") != 1:
        return False
    try:
        signed = bytes.fromhex(catalog["signature"]) + DOMAIN + catalog["body"].encode("utf-8")
        bindings.crypto_sign_open(signed, public)
        return True
    except (exceptions.BadSignatureError, ValueError, KeyError):
        return False


def public_key(seed):
    from gen_staff_keys import sign_pair

    return sign_pair(seed)[0]


def cmd_keygen(args):
    if args.key.exists():
        sys.exit(f"{args.key} exists: a new key would make every phone reject the catalog until an update")
    seed = os.urandom(32)
    args.key.parent.mkdir(parents=True, exist_ok=True)
    args.key.write_text(json.dumps({"seed": seed.hex(), "public": public_key(seed).hex()}, indent=2) + "\n", encoding="utf-8")
    print(f"seed -> {args.key} (keep it offline)\npublic key {public_key(seed).hex()}")


def cmd_build(args):
    if not args.base_url.startswith("https://"):
        sys.exit("--base-url must be https://")
    if args.max_points < 4:
        sys.exit("--max-points: an outline needs at least 4")
    base_url = args.base_url if args.base_url.endswith("/") else args.base_url + "/"
    spec = json.loads(args.packs.read_text(encoding="utf-8"))
    entries = [pack(item, args.packs.parent, base_url, args.max_points) for item in spec]
    text = body(entries, args.date or date.today().strftime("%Y%m%d"))
    seed = bytes.fromhex(json.loads(args.key.read_text(encoding="utf-8"))["seed"])
    catalog = sign(text, seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(catalog, ensure_ascii=False) + "\n", encoding="utf-8")
    for item, entry in zip(spec, entries):
        print(f"{args.packs.parent / item['file']} -> {entry['url']} ({entry['bytes']} bytes, {len(entry['outline'])} outline points)")
    print(f"catalog -> {args.out}")


def cmd_verify(args):
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    public = bytes.fromhex(args.public) if args.public else public_key(bytes.fromhex(json.loads(args.key.read_text(encoding="utf-8"))["seed"]))
    if not verify(catalog, public):
        sys.exit(f"{args.catalog}: the signature does not match")
    print(f"{args.catalog}: signed, {len(json.loads(catalog['body'])['packs'])} packs")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", type=Path, default=KEY, help=f"the signing seed; default {KEY.relative_to(ROOT)}")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("keygen", help="a new signing key")
    p = sub.add_parser("build", help="the signed catalog")
    p.add_argument("packs", type=Path, help="packs.json")
    p.add_argument("--base-url", required=True, help="where the packs are hosted, https://")
    p.add_argument("--date", help="the catalog's date, YYYYMMDD; today by default")
    p.add_argument("--max-points", type=int, default=MAX_POINTS, help="points in each outline at most")
    p.add_argument("--out", type=Path, default=OUT)
    p = sub.add_parser("verify", help="check a catalog's signature")
    p.add_argument("catalog", type=Path)
    p.add_argument("--public", help="the public key, hex; by default the one of --key")
    args = parser.parse_args(argv)
    {"keygen": cmd_keygen, "build": cmd_build, "verify": cmd_verify}[args.command](args)


if __name__ == "__main__":
    main()
