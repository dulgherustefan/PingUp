"""Tests for map_catalog.py, against the festival pack the app ships: python3 tools/test_map_catalog.py"""

import contextlib
import io
import json
import math
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import map_catalog as mc  # noqa: E402

BUNDLED = mc.ROOT / "app" / "src" / "main" / "assets" / "maps" / "20261013"
SEED = bytes(range(32))

try:
    import nacl  # noqa: F401

    SIGNS = True
except ImportError:
    SIGNS = False


def circle(points=720, radius=1.0, lon=25.0, lat=45.0):
    scale = math.cos(math.radians(lat))
    return [(lon + radius * math.cos(a) / scale, lat + radius * math.sin(a)) for a in (2 * math.pi * i / points for i in range(points))]


class OutlineTest(unittest.TestCase):
    def test_a_dense_outline_keeps_its_shape_with_few_points(self):
        ring = circle()
        simple = mc.outline(ring, max_points=60)
        self.assertLessEqual(len(simple), 60)
        self.assertGreaterEqual(len(simple), 12)
        self.assertEqual(ring[0], simple[0])
        self.assertTrue(set(simple) <= set(ring))
        self.assertAlmostEqual(1.0, mc.area(simple) / mc.area(ring), delta=0.02)

    def test_a_small_tolerance_keeps_every_corner(self):
        square = [(0.0, 0.0), (1.0, 0.0), (1.0, 0.5), (1.0, 1.0), (0.0, 1.0)]
        self.assertEqual([(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)], mc.simplify(square, 0.001))

    def test_the_largest_polygon_without_its_closing_point(self):
        island = [[0.0, 0.0], [0.1, 0.0], [0.1, 0.1], [0.0, 0.0]]
        land = [[1.0, 1.0], [3.0, 1.0], [3.0, 3.0], [1.0, 3.0], [1.0, 1.0]]
        multi = {"type": "Feature", "geometry": {"type": "MultiPolygon", "coordinates": [[island], [land, island]]}}
        self.assertEqual([(1.0, 1.0), (3.0, 1.0), (3.0, 3.0), (1.0, 3.0)], mc.largest_ring({"type": "FeatureCollection", "features": [multi]}))
        with self.assertRaises(ValueError):
            mc.largest_ring({"type": "Point", "coordinates": [0, 0]})


class Workspace(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        pack = self.dir / "costinesti"
        pack.mkdir()
        for name in ("costinesti.pmtiles", "manifest.json"):
            shutil.copyfile(BUNDLED / name, pack / name)
        self.key = self.dir / "key.json"
        self.key.write_text(json.dumps({"seed": SEED.hex()}), encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def run_main(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            mc.main(["--key", str(self.key), *args])
        return out.getvalue()

    def spec(self, *items):
        path = self.dir / "packs.json"
        path.write_text(json.dumps(list(items)), encoding="utf-8")
        return path


class PackTest(Workspace):
    def test_an_entry_comes_from_the_file_and_its_manifest(self):
        manifest = json.loads((BUNDLED / "manifest.json").read_text(encoding="utf-8"))
        entry = mc.pack({"file": "costinesti/costinesti.pmtiles"}, self.dir, "https://maps.example.org/packs/")
        self.assertEqual(
            ["id", "label", "url", "sha256", "bytes", "bbox", "outline", "minzoom", "maxzoom", "buildDate", "attribution"], list(entry),
        )
        self.assertEqual(("costinesti", "Costinești", "20261013"), (entry["id"], entry["label"], entry["buildDate"]))
        self.assertEqual("https://maps.example.org/packs/costinesti-20261013.pmtiles", entry["url"])
        self.assertEqual((manifest["sha256"], manifest["bytes"]), (entry["sha256"], entry["bytes"]))
        self.assertEqual([28.59, 43.92, 28.675, 44.01], entry["bbox"])
        self.assertEqual((0, 15), (entry["minzoom"], entry["maxzoom"]))
        self.assertEqual(manifest["attribution"], entry["attribution"])
        # no outline given: the bbox
        self.assertEqual(
            [{"lat": 43.92, "lon": 28.59}, {"lat": 43.92, "lon": 28.675}, {"lat": 44.01, "lon": 28.675}, {"lat": 44.01, "lon": 28.59}],
            entry["outline"],
        )

    def test_an_outline_file_is_simplified(self):
        ring = [list(p) for p in circle(lon=28.63, lat=43.96, radius=0.04)]
        (self.dir / "round.geojson").write_text(json.dumps({"type": "Polygon", "coordinates": [ring + ring[:1]]}), encoding="utf-8")
        entry = mc.pack({"file": "costinesti/costinesti.pmtiles", "outline": "round.geojson", "id": "round", "label": "Cerc"}, self.dir,
                        "https://maps.example.org/", max_points=40)
        self.assertEqual(("round", "Cerc"), (entry["id"], entry["label"]))
        # 500 m of tolerance on a 4.4 km circle: an octagon, every corner on the circle
        self.assertEqual(8, len(entry["outline"]))
        for p in entry["outline"]:
            self.assertAlmostEqual(0.04, math.hypot((p["lon"] - 28.63) * math.cos(math.radians(43.96)), p["lat"] - 43.96), delta=1e-4)

    def test_bad_ids_and_dates_are_refused(self):
        with self.assertRaises(ValueError):
            mc.pack({"file": "costinesti/costinesti.pmtiles", "id": "Costinești"}, self.dir, "https://x/")
        (self.dir / "costinesti" / "manifest.json").unlink()
        with self.assertRaises(ValueError):
            mc.pack({"file": "costinesti/costinesti.pmtiles", "id": "costinesti"}, self.dir, "https://x/")
        with self.assertRaises(ValueError):
            mc.body([{"id": "a"}, {"id": "a"}], "20261014")


@unittest.skipUnless(SIGNS, "PyNaCl is missing: pip install pynacl")
class SignedTest(Workspace):
    def test_the_catalog_is_signed_and_a_changed_body_is_not(self):
        out = self.dir / "catalog.json"
        printed = self.run_main(
            "build", str(self.spec({"file": "costinesti/costinesti.pmtiles"})), "--base-url", "https://maps.example.org/packs",
            "--date", "20261014", "--out", str(out),
        )
        self.assertIn("-> https://maps.example.org/packs/costinesti-20261013.pmtiles", printed)
        catalog = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(["format", "version", "body", "signature"], list(catalog))
        self.assertEqual(("pingup-maps", 1), (catalog["format"], catalog["version"]))
        body = json.loads(catalog["body"])
        self.assertEqual("20261014", body["generated"])
        self.assertEqual(["costinesti"], [p["id"] for p in body["packs"]])
        public = mc.public_key(SEED)
        self.assertTrue(mc.verify(catalog, public))
        self.assertFalse(mc.verify(dict(catalog, body=catalog["body"].replace("costinesti-2026", "evil-2026")), public))
        self.assertFalse(mc.verify(catalog, mc.public_key(bytes(32))))
        self.assertIn("signed, 1 packs", self.run_main("verify", str(out)))
        self.assertIn("signed", self.run_main("verify", str(out), "--public", public.hex()))

    def test_the_signature_is_the_event_packs_kind(self):
        from nacl import bindings

        catalog = mc.sign('{"generated":"20261014","packs":[]}', SEED)
        signed = bytes.fromhex(catalog["signature"]) + b"SP-MAPS-v1" + catalog["body"].encode("utf-8")
        self.assertEqual(b"SP-MAPS-v1" + catalog["body"].encode("utf-8"), bindings.crypto_sign_open(signed, mc.public_key(SEED)))

    def test_keygen_never_overwrites_a_key(self):
        self.key = self.dir / "new" / "key.json"
        printed = self.run_main("keygen")
        stored = json.loads(self.key.read_text(encoding="utf-8"))
        self.assertEqual(stored["public"], mc.public_key(bytes.fromhex(stored["seed"])).hex())
        self.assertIn(stored["public"], printed)
        with self.assertRaises(SystemExit):
            self.run_main("keygen")
        self.assertEqual(stored, json.loads(self.key.read_text(encoding="utf-8")))

    def test_only_https_hosts(self):
        with self.assertRaises(SystemExit):
            self.run_main("build", str(self.spec({"file": "costinesti/costinesti.pmtiles"})), "--base-url", "http://maps.example.org/")


if __name__ == "__main__":
    unittest.main()
