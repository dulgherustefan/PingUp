#!/usr/bin/env python3
"""Cut the world overview the apps ship out of a Protomaps planet build, reading only the parts it needs.

    python tools/map_overview.py --source https://maps.example.org/20261012.pmtiles --maxzoom 5 --dry-run
    python tools/map_overview.py --source https://maps.example.org/20261012.pmtiles --maxzoom 5
    python tools/map_overview.py --source planet.pmtiles --minzoom 7 --maxzoom 8 --bbox 20.2,43.6,29.8,48.3 \\
        --id ro-low --label "România"

Reads the archive's header and directories, then the tiles from --minzoom to --maxzoom (the whole world, or the
tiles that touch --bbox), with HTTP range requests or from a local file, and writes <id>.pmtiles and manifest.json
(PackManifest in core/map/MapPacks.kt: OVERVIEW for the world, AREA for a box) to --out. --dry-run prints the tile
count and the exact size of the archive it would write, from the directories alone, and writes nothing.

The tiles are copied unchanged, in the same schema, so one style draws them all; tiles the source stores once are
stored once here too. A box is its own pack: the overview's source must stop at one zoom everywhere, or MapLibre
draws nothing where a deeper tile is missing. Point --source at the team's copy of a pinned build: the Protomaps
docs ask for a copy, not hotlinks to build.protomaps.com. The data is OpenStreetMap, ODbL; the archive's metadata and
the manifest credit "© OpenStreetMap contributors". No dependencies: a PMTiles v3 reader and writer are below.
"""

import argparse
import gzip
import hashlib
import json
import struct
import sys
from bisect import bisect_left
from collections import namedtuple
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from map_extract import OSM_ATTRIBUTION, ROOT, http_source, tile_range  # noqa: E402

HEADER = struct.Struct("<7sB11Q6B4iB2i")
FIELDS = (
    "root_offset", "root_length", "metadata_offset", "metadata_length", "leaf_offset", "leaf_length", "data_offset",
    "data_length", "addressed_tiles", "tile_entries", "tile_contents", "clustered", "internal_compression",
    "tile_compression", "tile_type", "min_zoom", "max_zoom", "min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7",
    "center_zoom", "center_lon_e7", "center_lat_e7",
)
# the header and the root directory must fit in the first 16 KiB
FIRST_BYTES = 16384
NONE, GZIP = 1, 2
MVT = 1
# neighbouring tiles less than GAP apart are read in one request of at most CHUNK bytes
GAP = 64 * 1024
CHUNK = 8 * 1024 * 1024
WORLD = (-180.0, -85.0511287, 180.0, 85.0511287)

Entry = namedtuple("Entry", "tile_id offset length run_length")


def first_tileid(z):
    """The id of tile z/0/0: the tiles of every lower zoom come before it."""
    return ((1 << (2 * z)) - 1) // 3


def _rotate(n, x, y, rx, ry):
    if ry == 0:
        if rx == 1:
            x, y = n - 1 - x, n - 1 - y
        x, y = y, x
    return x, y


def zxy_to_tileid(z, x, y):
    """The tile's position on the zoom's Hilbert curve, after all lower zooms (PMTiles v3 spec)."""
    n = 1 << z
    if not (0 <= x < n and 0 <= y < n):
        raise ValueError(f"no tile {z}/{x}/{y}")
    d = 0
    s = n >> 1
    while s:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        x, y = _rotate(n, x, y, rx, ry)
        s >>= 1
    return first_tileid(z) + d


def tileid_to_zxy(tile_id):
    z = 0
    while first_tileid(z + 1) <= tile_id:
        z += 1
    d = tile_id - first_tileid(z)
    x = y = 0
    s = 1
    while s < 1 << z:
        rx = 1 & (d // 2)
        ry = 1 & (d ^ rx)
        x, y = _rotate(s, x, y, rx, ry)
        x += s * rx
        y += s * ry
        d //= 4
        s *= 2
    return z, x, y


def read_varint(data, pos):
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, pos
        shift += 7


def write_varint(out, value):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def read_header(data):
    if len(data) < HEADER.size or data[:7] != b"PMTiles" or data[7] != 3:
        raise ValueError("not a PMTiles v3 archive")
    return dict(zip(FIELDS, HEADER.unpack_from(data)[2:]))


def write_header(header):
    return HEADER.pack(b"PMTiles", 3, *(header[f] for f in FIELDS))


def gunzip(data, compression):
    if compression == GZIP:
        return gzip.decompress(data)
    if compression == NONE:
        return data
    raise ValueError(f"directories compressed with {compression}: only none and gzip are read")


def deflate(data):
    # mtime 0: the same input gives the same bytes, so a dry run knows the size
    return gzip.compress(data, compresslevel=9, mtime=0)


def read_directory(data):
    count, pos = read_varint(data, 0)
    columns = []
    for _ in range(3):
        column = []
        for _ in range(count):
            value, pos = read_varint(data, pos)
            column.append(value)
        columns.append(column)
    ids, runs, lengths = columns
    for i in range(1, count):
        ids[i] += ids[i - 1]
    entries = []
    for i in range(count):
        raw, pos = read_varint(data, pos)
        if raw == 0 and i == 0:
            raise ValueError("the first directory entry has no offset")
        offset = entries[i - 1].offset + entries[i - 1].length if raw == 0 else raw - 1
        entries.append(Entry(ids[i], offset, lengths[i], runs[i]))
    return entries


def write_directory(entries):
    out = bytearray()
    write_varint(out, len(entries))
    last = 0
    for e in entries:
        write_varint(out, e.tile_id - last)
        last = e.tile_id
    for e in entries:
        write_varint(out, e.run_length)
    for e in entries:
        write_varint(out, e.length)
    for i, e in enumerate(entries):
        follows = i > 0 and e.offset == entries[i - 1].offset + entries[i - 1].length
        write_varint(out, 0 if follows else e.offset + 1)
    return deflate(bytes(out))


def file_source(path):
    def get_bytes(offset, length):
        with open(path, "rb") as f:
            f.seek(offset)
            return f.read(length)

    return get_bytes


def open_source(spec):
    return http_source(spec) if spec.startswith(("https://", "http://")) else file_source(Path(spec))


class Archive:
    """A PMTiles v3 archive read through get_bytes(offset, length): a local file or HTTP range requests."""

    def __init__(self, get_bytes):
        self.get_bytes = get_bytes
        first = get_bytes(0, FIRST_BYTES)
        self.header = h = read_header(first)
        end = h["root_offset"] + h["root_length"]
        raw = first[h["root_offset"]:end] if end <= len(first) else get_bytes(h["root_offset"], h["root_length"])
        self.root = read_directory(gunzip(raw, h["internal_compression"]))

    def _read(self, offset, length):
        data = self.get_bytes(offset, length)
        if len(data) != length:
            raise IOError(f"asked for {length} bytes at {offset}, got {len(data)}")
        return data

    def _leaf(self, entry):
        raw = self._read(self.header["leaf_offset"] + entry.offset, entry.length)
        return read_directory(gunzip(raw, self.header["internal_compression"]))

    def metadata(self):
        h = self.header
        if not h["metadata_length"]:
            return {}
        return json.loads(gunzip(self._read(h["metadata_offset"], h["metadata_length"]), h["internal_compression"]))

    def find(self, wanted):
        """{tile id: (offset in the tile data, length)} for the sorted ids [wanted] the archive holds; only the leaf
        directories over them are read."""
        found = {}

        def walk(entries, ids, end):
            for i, e in enumerate(entries):
                if e.run_length:
                    hi = e.tile_id + e.run_length
                else:
                    hi = entries[i + 1].tile_id if i + 1 < len(entries) else end
                a = bisect_left(ids, e.tile_id)
                b = len(ids) if hi is None else bisect_left(ids, hi)
                if a == b:
                    continue
                if e.run_length:
                    for tile_id in ids[a:b]:
                        found[tile_id] = (e.offset, e.length)
                else:
                    walk(self._leaf(e), ids[a:b], hi)

        walk(self.root, wanted, None)
        return found

    def entries(self):
        """Every tile entry, with all leaf directories read: for small archives."""

        def walk(entries):
            for e in entries:
                if e.run_length:
                    yield e
                else:
                    yield from walk(self._leaf(e))

        return list(walk(self.root))

    def tile(self, tile_id):
        located = self.find([tile_id]).get(tile_id)
        if located is None:
            return None
        return self._read(self.header["data_offset"] + located[0], located[1])


def wanted_ids(minzoom, maxzoom, bbox=None):
    """Sorted tile ids from minzoom to maxzoom: every tile, or those that touch bbox (west past east crosses 180°)."""
    if bbox is None:
        return range(first_tileid(minzoom), first_tileid(maxzoom + 1))
    west, south, east, north = bbox
    boxes = [(west, south, east, north)] if west <= east else [(west, south, 180.0, north), (-180.0, south, east, north)]
    ids = set()
    for z in range(minzoom, maxzoom + 1):
        for box in boxes:
            xs, ys = tile_range(z, *box)
            ids.update(zxy_to_tileid(z, x, y) for x in xs for y in ys)
    return sorted(ids)


def layout(found):
    """The new archive's entries and the source spans to copy, in its order. A tile whose bytes the source shares
    with another tile shares them here too, and neighbours with the same bytes make one run."""
    entries, spans, placed = [], [], {}
    size = 0
    for tile_id in sorted(found):
        offset, length = found[tile_id]
        if offset not in placed:
            placed[offset] = size
            spans.append((offset, length))
            size += length
        last = entries[-1] if entries else None
        if last and last.offset == placed[offset] and last.tile_id + last.run_length == tile_id:
            entries[-1] = last._replace(run_length=last.run_length + 1)
        else:
            entries.append(Entry(tile_id, placed[offset], length, 1))
    return entries, spans, size


def directories(entries, root_limit=FIRST_BYTES - HEADER.size, leaf_size=4096):
    """The root directory and the leaves; leaves only when the root alone would not fit, as large as it takes."""
    root = write_directory(entries)
    if len(root) <= root_limit:
        return root, b""
    while True:
        leaves, pointers = bytearray(), []
        for start in range(0, len(entries), leaf_size):
            leaf = write_directory(entries[start:start + leaf_size])
            pointers.append(Entry(entries[start].tile_id, len(leaves), len(leaf), 0))
            leaves += leaf
        root = write_directory(pointers)
        if len(root) <= root_limit:
            return root, bytes(leaves)
        leaf_size *= 2


Plan = namedtuple("Plan", "header root metadata leaves spans size")


def plan(archive, minzoom, maxzoom, bbox=None, root_limit=FIRST_BYTES - HEADER.size, leaf_size=4096):
    """Everything about the new archive but the tile bytes: its exact size is known before any tile is read."""
    source = archive.header
    if source["tile_compression"] not in (NONE, GZIP):
        raise ValueError("MapLibre reads tiles that are gzip-compressed or not compressed at all")
    minzoom = max(minzoom, source["min_zoom"])
    maxzoom = min(maxzoom, source["max_zoom"])
    found = archive.find(wanted_ids(minzoom, maxzoom, bbox))
    entries, spans, size = layout(found)
    root, leaves = directories(entries, root_limit, leaf_size)
    meta = {k: v for k, v in archive.metadata().items() if k not in ("bounds", "center", "minzoom", "maxzoom")}
    meta["attribution"] = OSM_ATTRIBUTION
    metadata = deflate(json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    if bbox is None:
        west, south, east, north = (source[k] / 1e7 for k in ("min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7"))
    else:
        west, south, east, north = bbox
    offset = HEADER.size
    header = {"root_offset": offset, "root_length": len(root)}
    offset += len(root)
    header.update(metadata_offset=offset, metadata_length=len(metadata))
    offset += len(metadata)
    header.update(leaf_offset=offset, leaf_length=len(leaves))
    offset += len(leaves)
    header.update(
        data_offset=offset, data_length=size, addressed_tiles=len(found), tile_entries=len(entries),
        tile_contents=len(spans), clustered=1, internal_compression=GZIP, tile_compression=source["tile_compression"],
        tile_type=source["tile_type"], min_zoom=minzoom, max_zoom=maxzoom,
        min_lon_e7=round(west * 1e7), min_lat_e7=round(south * 1e7), max_lon_e7=round(east * 1e7), max_lat_e7=round(north * 1e7),
        center_zoom=minzoom, center_lon_e7=0 if bbox is None else round((west + east) / 2 * 1e7),
        center_lat_e7=0 if bbox is None else round((south + north) / 2 * 1e7),
    )
    return Plan(header, root, metadata, leaves, spans, offset + size)


def fetch(archive, spans):
    """The bytes of each (offset, length) span of the tile data, read in few requests."""
    data = {}
    ordered = sorted(set(spans))
    i = 0
    while i < len(ordered):
        start = ordered[i][0]
        end = start + ordered[i][1]
        j = i + 1
        while j < len(ordered) and ordered[j][0] - end <= GAP and ordered[j][0] + ordered[j][1] - start <= CHUNK:
            end = max(end, ordered[j][0] + ordered[j][1])
            j += 1
        block = archive._read(archive.header["data_offset"] + start, end - start)
        for offset, length in ordered[i:j]:
            data[offset] = block[offset - start:offset - start + length]
        i = j
    return data


def write(archive, plan, path):
    """Writes the archive to path through a temporary file; gives its SHA-256."""
    digest = hashlib.sha256()
    tiles = fetch(archive, plan.spans)
    temp = path.with_name(path.name + ".tmp")
    with open(temp, "wb") as out:
        for part in [write_header(plan.header), plan.root, plan.metadata, plan.leaves] + [tiles[o] for o, _ in plan.spans]:
            out.write(part)
            digest.update(part)
    temp.replace(path)
    if path.stat().st_size != plan.size:
        raise IOError(f"{path} has {path.stat().st_size} bytes, the plan said {plan.size}")
    return digest.hexdigest()


def build_date(metadata):
    """The day of the OpenStreetMap data in the build, as yyyyMMdd; today when the build doesn't say."""
    stamp = str(metadata.get("planetiler:osm:osmosisreplicationtime", ""))[:10].replace("-", "")
    return stamp if len(stamp) == 8 and stamp.isdigit() else date.today().strftime("%Y%m%d")


def manifest(pack_id, label, day, file, digest, plan, bbox):
    h = plan.header
    bounds = [h[k] / 1e7 for k in ("min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7")]
    return {
        "id": pack_id, "kind": "OVERVIEW" if bbox is None else "AREA", "buildDate": day, "file": file, "sha256": digest,
        "bytes": plan.size, "bbox": bounds, "minzoom": h["min_zoom"], "maxzoom": h["max_zoom"],
        "attribution": OSM_ATTRIBUTION, "label": label,
    }


def parse_bbox(text):
    values = [float(v) for v in text.split(",")]
    if len(values) != 4 or not (-90 <= values[1] < values[3] <= 90) or not all(-180 <= v <= 180 for v in values[::2]):
        raise argparse.ArgumentTypeError("west,south,east,north in degrees")
    return tuple(values)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, help="the planet build: an https:// URL that serves byte ranges, or a file")
    parser.add_argument("--maxzoom", type=int, required=True, help="deepest zoom to copy; the world at 5 is about 20 MB")
    parser.add_argument("--minzoom", type=int, default=0)
    parser.add_argument("--bbox", type=parse_bbox, help="west,south,east,north: only the tiles that touch it, as an AREA pack")
    parser.add_argument("--id", help="pack id; world by default, required with --bbox")
    parser.add_argument("--label", help="the name the app lists; World by default")
    parser.add_argument("--date", help="build date, YYYYMMDD; by default the day of the build's OpenStreetMap data")
    parser.add_argument("--out", type=Path, help="default tools/out/maps/<id>/<date>")
    parser.add_argument("--dry-run", action="store_true", help="print the tile count and the exact size, write nothing")
    args = parser.parse_args(argv)
    if args.bbox is not None and not args.id:
        parser.error("--bbox needs --id")
    if not 0 <= args.minzoom <= args.maxzoom <= 15:
        parser.error("zooms go from 0 to 15, --minzoom up to --maxzoom")
    if args.date and (len(args.date) != 8 or not args.date.isdigit()):
        parser.error("--date is YYYYMMDD")

    archive = Archive(open_source(args.source))
    if archive.header["tile_type"] != MVT:
        sys.exit("the source does not hold vector tiles")
    result = plan(archive, args.minzoom, args.maxzoom, args.bbox)
    h = result.header
    print(
        f"{h['addressed_tiles']} tiles ({h['tile_contents']} distinct), zoom {h['min_zoom']}-{h['max_zoom']}: "
        f"{result.size} bytes ({result.size / 1e6:.2f} MB)",
    )
    if args.dry_run:
        return

    pack_id = args.id or "world"
    day = args.date or build_date(archive.metadata())
    out = args.out or ROOT / "tools" / "out" / "maps" / pack_id / day
    out.mkdir(parents=True, exist_ok=True)
    file = f"{pack_id}.pmtiles"
    digest = write(archive, result, out / file)
    check = Archive(file_source(out / file))
    if check.header["addressed_tiles"] != h["addressed_tiles"] or sum(e.run_length for e in check.entries()) != h["addressed_tiles"]:
        sys.exit(f"{out / file} does not read back")
    label = args.label or ("World" if args.bbox is None else pack_id)
    text = json.dumps(manifest(pack_id, label, day, file, digest, result, args.bbox), indent=2, ensure_ascii=False) + "\n"
    (out / "manifest.json").write_text(text, encoding="utf-8")
    print(f"wrote {out / file} (sha256 {digest}) and its manifest.json")


if __name__ == "__main__":
    main()
