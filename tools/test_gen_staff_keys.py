"""Tests for the root key in gen_staff_keys.py: python3 tools/test_gen_staff_keys.py (needs PyNaCl)"""

import json
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_staff_keys as keys  # noqa: E402
from nacl.signing import VerifyKey  # noqa: E402

PASSPHRASE = "cort verde la poarta trei"


class RootFileTest(unittest.TestCase):
    def setUp(self):
        # the smallest Argon2id cost, so the tests don't spend a gigabyte and seconds on every file
        patch = mock.patch.object(keys, "ROOT_KDF", (1, 8192))
        patch.start()
        self.addCleanup(patch.stop)

    def test_the_file_opens_only_with_its_passphrase(self):
        seed = os.urandom(32)
        record = keys.seal_root(seed, PASSPHRASE)
        self.assertNotIn(seed.hex(), json.dumps(record))
        self.assertEqual(keys.sign_pair(seed)[0].hex(), record["public"])
        self.assertEqual(seed, keys.open_root(record, PASSPHRASE))
        with self.assertRaises(SystemExit):
            keys.open_root(record, "cort verde la poarta patru")

    def test_a_file_with_another_public_key_is_refused(self):
        record = keys.seal_root(os.urandom(32), PASSPHRASE)
        record["public"] = keys.sign_pair(os.urandom(32))[0].hex()
        with self.assertRaises(SystemExit):
            keys.open_root(record, PASSPHRASE)

    def test_a_new_passphrase_is_long_and_typed_twice(self):
        with mock.patch.object(keys.getpass, "getpass", side_effect=[PASSPHRASE, PASSPHRASE]):
            self.assertEqual(PASSPHRASE, keys.new_passphrase())
        with mock.patch.object(keys.getpass, "getpass", side_effect=["scurta", "scurta"]):
            with self.assertRaises(SystemExit):
                keys.new_passphrase()
        with mock.patch.object(keys.getpass, "getpass", side_effect=[PASSPHRASE, PASSPHRASE + "!"]):
            with self.assertRaises(SystemExit):
                keys.new_passphrase()


class CeremonyTest(unittest.TestCase):
    """The commands, run against a temporary repo: tools/out and the app's staff_public.json live under it."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.usb = self.dir / "usb"
        self.usb.mkdir()
        out = self.dir / "tools" / "out"
        public = self.dir / "staff_public.json"
        public.write_text(json.dumps({"box": "00" * 32, "sign": "00" * 32}), encoding="utf-8")
        for name, value in {"ROOT": self.dir, "OUT": out, "CHAIN": out / "chain.json", "PUBLIC": public,
                            "ROOT_KDF": (1, 8192)}.items():
            patch = mock.patch.object(keys, name, value)
            patch.start()
            self.addCleanup(patch.stop)
        patch = mock.patch.object(keys.getpass, "getpass", return_value=PASSPHRASE)
        patch.start()
        self.addCleanup(patch.stop)

    def chain(self) -> dict:
        return json.loads(keys.CHAIN.read_text(encoding="utf-8"))

    def test_root_writes_the_seed_only_to_the_offline_files(self):
        files = [self.usb / "pingup-root.json", self.usb / "copy.json"]
        keys.main(["root", "--out", *map(str, files)])
        records = [json.loads(f.read_text(encoding="utf-8")) for f in files]
        self.assertEqual(records[0], records[1])
        self.assertEqual({"public": records[0]["public"]}, self.chain()["root"])
        public = json.loads(keys.PUBLIC.read_text(encoding="utf-8"))
        self.assertEqual(records[0]["public"], public["root"])
        self.assertEqual("00" * 32, public["box"], "the build's shared keys stay")
        self.assertEqual(0o600, files[0].stat().st_mode & 0o777)
        with self.assertRaises(SystemExit):
            keys.main(["root", "--out", str(self.usb / "again.json")])

    def test_the_organiser_is_signed_with_the_root_from_the_file(self):
        root_file = self.usb / "pingup-root.json"
        keys.main(["root", "--out", str(root_file)])
        keys.main(["organiser", "--name", "Ping Up", "--root", str(root_file)])
        cert = bytes.fromhex(self.chain()["organiser"]["cert"])
        root_public = bytes.fromhex(self.chain()["root"]["public"])
        self.assertEqual(keys.ROLE_ORGANISER, cert[1])
        self.assertEqual(keys.key_id(root_public), cert[42:50])
        VerifyKey(root_public).verify(b"SP-CERT-v1" + cert[:-64], cert[-64:])
        start, end = struct.unpack(">II", cert[34:42])
        self.assertGreater(end, start)

    def test_the_organiser_needs_the_root_file(self):
        keys.main(["root", "--out", str(self.usb / "pingup-root.json")])
        with self.assertRaises(SystemExit):
            keys.main(["organiser", "--name", "Ping Up"])

    def test_the_root_file_must_be_this_chains(self):
        keys.main(["root", "--out", str(self.usb / "pingup-root.json")])
        other = self.usb / "other.json"
        other.write_text(json.dumps(keys.seal_root(os.urandom(32), PASSPHRASE)), encoding="utf-8")
        with self.assertRaises(SystemExit):
            keys.main(["organiser", "--name", "Ping Up", "--root", str(other)])

    def test_a_chain_with_the_root_seed_inside_still_signs(self):
        """Chains made before the root file (exercises, the demo) keep the seed in chain.json."""
        seed = os.urandom(32)
        keys.save_json(keys.CHAIN, {"root": {"seed": seed.hex()}})
        keys.main(["organiser", "--name", "Demo"])
        cert = bytes.fromhex(self.chain()["organiser"]["cert"])
        VerifyKey(keys.sign_pair(seed)[0]).verify(b"SP-CERT-v1" + cert[:-64], cert[-64:])


if __name__ == "__main__":
    unittest.main()
