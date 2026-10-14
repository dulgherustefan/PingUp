"""Tests for map_overview.py, against the festival pack the app ships: python3 tools/test_map_overview.py"""

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import map_extract  # noqa: E402
import map_overview as mo  # noqa: E402

PACK = mo.ROOT / "app" / "src" / "main" / "assets" / "maps" / "20261013" / "costinesti.pmtiles"
DATA = PACK.read_bytes()


def source():
    return mo.Archive(mo.file_source(PACK))


class Workspace(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def tearDown(self):
        for f in sorted(self.dir.rglob("*"), reverse=True):
            f.rmdir() if f.is_dir() else f.unlink()
        self.dir.rmdir()

    def written(self, plan, name="out.pmtiles"):
        path = self.dir / name
        digest = mo.write(source(), plan, path)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest)
        return path, mo.Archive(mo.file_source(path))

    def assertSameTiles(self, copy, ids):
        original = source()
        for tile_id in ids:
            self.assertEqual(original.tile(tile_id), copy.tile(tile_id), mo.tileid_to_zxy(tile_id))


class HilbertTest(unittest.TestCase):
    def test_spec_vectors(self):
        self.assertEqual(0, mo.zxy_to_tileid(0, 0, 0))
        self.assertEqual([1, 2, 3, 4], [mo.zxy_to_tileid(1, x, y) for x, y in ((0, 0), (0, 1), (1, 1), (1, 0))])
        self.assertEqual(19078479, mo.zxy_to_tileid(12, 3423, 1763))
        self.assertEqual(mo.first_tileid(15), mo.zxy_to_tileid(15, 0, 0))
        with self.assertRaises(ValueError):
            mo.zxy_to_tileid(3, 8, 0)

    def test_round_trip(self):
        for z in range(0, 16, 3):
            n = 1 << z
            for x, y in ((0, 0), (n - 1, 0), (n // 3, n // 2), (n - 1, n - 1)):
                self.assertEqual((z, x, y), mo.tileid_to_zxy(mo.zxy_to_tileid(z, x, y)))

    def test_varints(self):
        for value in (0, 1, 127, 128, 300, 2**35 + 7):
            out = bytearray()
            mo.write_varint(out, value)
            self.assertEqual((value, len(out)), mo.read_varint(bytes(out), 0))


class ReadTest(unittest.TestCase):
    def test_the_shipped_pack(self):
        archive = source()
        h = archive.header
        self.assertEqual((0, 15, 183, 155, 138), (h["min_zoom"], h["max_zoom"], h["addressed_tiles"], h["tile_entries"], h["tile_contents"]))
        self.assertEqual((1, mo.GZIP, mo.GZIP, mo.MVT), (h["clustered"], h["internal_compression"], h["tile_compression"], h["tile_type"]))
        entries = archive.entries()
        self.assertEqual(155, len(entries))
        self.assertEqual(183, sum(e.run_length for e in entries))
        self.assertEqual("4.15.2", archive.metadata()["version"])

    def test_the_festival_box_is_its_183_tiles(self):
        # the same count as map_extract.py's and the PMTiles header's
        self.assertEqual(183, len(source().find(mo.wanted_ids(0, 15, map_extract.BBOX))))

    def test_a_header_round_trips(self):
        header = source().header
        self.assertEqual(header, mo.read_header(mo.write_header(header)))
        self.assertEqual(DATA[:127], mo.write_header(header))
        with self.assertRaises(ValueError):
            mo.read_header(b"PMTiles\x02" + bytes(119))


class OverviewTest(Workspace):
    def test_the_dry_run_size_is_the_size_written(self):
        plan = mo.plan(source(), 0, 6)
        # the festival pack holds one parent tile per zoom up to 12
        self.assertEqual(7, plan.header["addressed_tiles"])
        path, copy = self.written(plan)
        self.assertEqual(plan.size, path.stat().st_size)
        h = copy.header
        self.assertEqual((0, 6, 7, 1), (h["min_zoom"], h["max_zoom"], h["addressed_tiles"], h["clustered"]))
        self.assertEqual((mo.GZIP, mo.MVT), (h["tile_compression"], h["tile_type"]))
        self.assertEqual(source().header["min_lon_e7"], h["min_lon_e7"])
        self.assertSameTiles(copy, [e.tile_id for e in copy.entries()])
        self.assertEqual(list(range(7)), [mo.tileid_to_zxy(e.tile_id)[0] for e in copy.entries()])

    def test_shared_tiles_stay_shared(self):
        plan = mo.plan(source(), 0, 15)
        path, copy = self.written(plan)
        h = copy.header
        self.assertEqual((183, 138), (h["addressed_tiles"], h["tile_contents"]))
        self.assertLessEqual(h["tile_entries"], 155)
        self.assertEqual(source().header["data_length"], h["data_length"])
        self.assertSameTiles(copy, [e.tile_id + k for e in copy.entries() for k in range(e.run_length)])

    def test_a_box_is_its_own_pack(self):
        plan = mo.plan(source(), 7, 9, map_extract.BBOX)
        self.assertEqual(3, plan.header["addressed_tiles"])
        path, copy = self.written(plan)
        self.assertEqual([7, 8, 9], [mo.tileid_to_zxy(e.tile_id)[0] for e in copy.entries()])
        self.assertEqual(round(28.59 * 1e7), copy.header["min_lon_e7"])
        self.assertEqual(round(44.01 * 1e7), copy.header["max_lat_e7"])
        self.assertSameTiles(copy, [e.tile_id for e in copy.entries()])

    def test_a_box_across_the_antimeridian_has_both_sides(self):
        ids = mo.wanted_ids(2, 2, (170.0, -20.0, -170.0, -10.0))
        self.assertEqual([(2, 3, 2), (2, 0, 2)], sorted((mo.tileid_to_zxy(i) for i in ids), key=lambda t: -t[1]))

    def test_leaf_directories_when_the_root_is_too_big(self):
        plain = mo.plan(source(), 0, 15)
        leafy = mo.plan(source(), 0, 15, root_limit=60, leaf_size=16)
        self.assertEqual(0, plain.header["leaf_length"])
        self.assertGreater(leafy.header["leaf_length"], 0)
        self.assertLessEqual(leafy.header["root_length"], 60)
        path, copy = self.written(leafy)
        self.assertEqual(leafy.size, path.stat().st_size)
        self.assertGreater(len(copy.root), 1)
        self.assertTrue(all(e.run_length == 0 for e in copy.root))
        _, flat = self.written(plain, "plain.pmtiles")
        self.assertEqual(flat.entries(), copy.entries())
        self.assertSameTiles(copy, [e.tile_id for e in copy.entries()][::7])

    def test_the_metadata_credits_contributors_and_the_landcover(self):
        _, copy = self.written(mo.plan(source(), 0, 3))
        meta = copy.metadata()
        self.assertEqual(map_extract.OSM_ATTRIBUTION + " " + map_extract.ESA_ATTRIBUTION, meta["attribution"])
        self.assertIn(">© OpenStreetMap contributors<", meta["attribution"])
        self.assertEqual(source().metadata()["vector_layers"], meta["vector_layers"])
        # past zoom 7 there is no landcover to credit
        _, deep = self.written(mo.plan(source(), 8, 9), "deep.pmtiles")
        self.assertEqual(map_extract.OSM_ATTRIBUTION, deep.metadata()["attribution"])


class HttpTest(Workspace):
    def fake_host(self, status=206):
        requests = []

        class Response(io.BytesIO):
            def __init__(self, body):
                super().__init__(body)
                self.status = status

        def urlopen(request, timeout):
            requests.append(request)
            first, last = (int(v) for v in request.get_header("Range").removeprefix("bytes=").split("-"))
            return Response(DATA[first:last + 1])

        return requests, mock.patch("urllib.request.urlopen", urlopen)

    def test_reads_ranges_with_its_own_user_agent(self):
        requests, patch = self.fake_host()
        with patch:
            remote = mo.Archive(mo.open_source("https://maps.example.org/planet.pmtiles"))
            plan = mo.plan(remote, 0, 6)
            path = self.dir / "remote.pmtiles"
            mo.write(remote, plan, path)
        _, local = self.written(mo.plan(source(), 0, 6), "local.pmtiles")
        self.assertEqual((self.dir / "local.pmtiles").read_bytes(), path.read_bytes())
        self.assertTrue(requests)
        for request in requests:
            self.assertEqual("pingup-map-tools/1.0", request.get_header("User-agent"))
            self.assertTrue(request.get_header("Range").startswith("bytes="))
        # the header, the metadata and the tiles, which lie together: three requests
        self.assertEqual(3, len(requests))

    def test_a_host_without_ranges_stops_it(self):
        _, patch = self.fake_host(status=200)
        with patch, self.assertRaises(SystemExit):
            mo.Archive(mo.open_source("https://maps.example.org/planet.pmtiles"))


class CliTest(Workspace):
    def run_main(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            mo.main(["--source", str(PACK), *args])
        return out.getvalue()

    def test_a_dry_run_prints_the_count_and_exact_size_and_writes_nothing(self):
        printed = self.run_main("--maxzoom", "6", "--dry-run", "--out", str(self.dir / "x"))
        size = mo.plan(source(), 0, 6).size
        self.assertIn("7 tiles", printed)
        self.assertIn(f"{size} bytes", printed)
        self.assertFalse((self.dir / "x").exists())

    def test_the_manifest_is_a_pack_manifest(self):
        self.run_main("--maxzoom", "5", "--out", str(self.dir))
        manifest = json.loads((self.dir / "manifest.json").read_text(encoding="utf-8"))
        tiles = self.dir / "world.pmtiles"
        self.assertEqual(
            {"id", "kind", "buildDate", "file", "sha256", "bytes", "bbox", "minzoom", "maxzoom", "attribution", "label"}, set(manifest),
        )
        self.assertEqual(("world", "OVERVIEW", "world.pmtiles", "World"), (manifest["id"], manifest["kind"], manifest["file"], manifest["label"]))
        self.assertEqual(hashlib.sha256(tiles.read_bytes()).hexdigest(), manifest["sha256"])
        self.assertEqual(tiles.stat().st_size, manifest["bytes"])
        self.assertEqual((0, 5), (manifest["minzoom"], manifest["maxzoom"]))
        # the day of the build's OpenStreetMap data
        self.assertEqual("20261013", manifest["buildDate"])
        self.assertEqual([28.59, 43.92, 28.675, 44.01], manifest["bbox"])
        self.assertEqual(map_extract.attribution(0), manifest["attribution"])

    def test_a_box_needs_an_id_and_makes_an_area(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            self.run_main("--maxzoom", "9", "--bbox", "28.59,43.92,28.675,44.01")
        self.run_main("--minzoom", "7", "--maxzoom", "9", "--bbox", "28.59,43.92,28.675,44.01", "--id", "fest", "--out", str(self.dir))
        manifest = json.loads((self.dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(("fest", "AREA", 7, 9), (manifest["id"], manifest["kind"], manifest["minzoom"], manifest["maxzoom"]))


if __name__ == "__main__":
    unittest.main()
