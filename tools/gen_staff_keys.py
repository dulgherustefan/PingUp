#!/usr/bin/env python3
"""Genereaza cheile de staff ale unui eveniment si QR-urile pentru echipe si ancore.

Cheile publice ajung in aplicatie (app/src/main/assets/staff_public.json). Semintele secrete
raman in tools/out/ (ignorat de git) si circula doar prin QR-urile de staff.

  pip install pynacl "qrcode[pil]"
  python tools/gen_staff_keys.py --teams "Medical 1" "Medical 2" --anchors main-stage bar

Rularile ulterioare refolosesc semintele din tools/out/staff_seeds.json, ca sa poti adauga
echipe fara sa schimbi cheile. --new genereaza chei noi (QR-urile vechi nu mai sunt valabile).
--demo copiaza semintele si in build-ul debug, pentru "activeaza staff fara QR" din modul demo;
nu folosi --demo pentru un eveniment real.
"""

import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

try:
    from nacl import bindings
except ImportError:
    sys.exit('Lipseste PyNaCl: pip install pynacl "qrcode[pil]"')

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools" / "out"
SEEDS = OUT / "staff_seeds.json"
PUBLIC = ROOT / "app" / "src" / "main" / "assets" / "staff_public.json"
DEMO = ROOT / "app" / "src" / "debug" / "assets" / "demo_staff.json"

ROLE_STAFF = 1
ROLE_ANCHOR = 2
TEAM_BYTES = 24
ZONE_BYTES = 32


def load_or_create_seeds(new: bool) -> tuple[bytes, bytes]:
    if SEEDS.exists() and not new:
        data = json.loads(SEEDS.read_text(encoding="utf-8"))
        return bytes.fromhex(data["boxSeed"]), bytes.fromhex(data["signSeed"])
    box_seed, sign_seed = os.urandom(32), os.urandom(32)
    OUT.mkdir(parents=True, exist_ok=True)
    SEEDS.write_text(json.dumps({"boxSeed": box_seed.hex(), "signSeed": sign_seed.hex()}, indent=2), encoding="utf-8")
    return box_seed, sign_seed


def qr_payload(box_seed: bytes, sign_seed: bytes, role: int, team: str, zone: str) -> str:
    team_b, zone_b = team.encode("utf-8"), zone.encode("utf-8")
    if len(team_b) > TEAM_BYTES:
        sys.exit(f'Numele echipei "{team}" depaseste {TEAM_BYTES} octeti')
    if len(zone_b) > ZONE_BYTES:
        sys.exit(f'Zona "{zone}" depaseste {ZONE_BYTES} octeti')
    raw = box_seed + sign_seed + bytes([role, len(team_b)]) + team_b + bytes([len(zone_b)]) + zone_b
    return "SPS1." + base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def save_qr(text: str, path: Path) -> bool:
    try:
        import qrcode
    except ImportError:
        return False
    qrcode.make(text, border=2, box_size=10).save(path)
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--teams", nargs="*", default=["Medical 1", "Securitate 1"], help="nume de echipe de staff")
    parser.add_argument("--anchors", nargs="*", default=[], help="id-uri de zone din venue.json pentru ancore")
    parser.add_argument("--new", action="store_true", help="genereaza chei noi in loc sa le refoloseasca")
    parser.add_argument("--demo", action="store_true", help="include semintele si in build-ul debug")
    args = parser.parse_args()

    box_seed, sign_seed = load_or_create_seeds(args.new)
    box_public, _ = bindings.crypto_box_seed_keypair(box_seed)
    sign_public, _ = bindings.crypto_sign_seed_keypair(sign_seed)

    PUBLIC.parent.mkdir(parents=True, exist_ok=True)
    PUBLIC.write_text(json.dumps({"box": box_public.hex(), "sign": sign_public.hex()}, indent=2) + "\n", encoding="utf-8")
    print(f"chei publice -> {PUBLIC.relative_to(ROOT)}")

    if args.demo:
        DEMO.parent.mkdir(parents=True, exist_ok=True)
        DEMO.write_text(json.dumps({"boxSeed": box_seed.hex(), "signSeed": sign_seed.hex()}, indent=2) + "\n", encoding="utf-8")
        print(f"seminte demo -> {DEMO.relative_to(ROOT)} (doar build-ul debug)")
    elif DEMO.exists():
        DEMO.unlink()
        print("semintele demo au fost sterse: build-ul debug nu mai poate activa staff fara QR")

    cards = [(ROLE_STAFF, team, "") for team in args.teams]
    cards += [(ROLE_ANCHOR, f"Ancora {zone}"[:TEAM_BYTES], zone) for zone in args.anchors]
    lines = []
    for role, team, zone in cards:
        text = qr_payload(box_seed, sign_seed, role, team, zone)
        name = re.sub(r"[^A-Za-z0-9]+", "_", team).strip("_").lower()
        image = OUT / f"{'anchor' if role == ROLE_ANCHOR else 'staff'}_{name}.png"
        saved = save_qr(text, image)
        lines.append(f"{team}\t{text}")
        print(f"{'ancora' if role == ROLE_ANCHOR else 'staff'}: {team} -> {image.relative_to(ROOT) if saved else '(fara imagine: pip install qrcode[pil])'}")
    (OUT / "staff_qr.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"continutul QR-urilor -> {(OUT / 'staff_qr.txt').relative_to(ROOT)}")
    print("Reconstruieste aplicatia dupa schimbarea cheilor publice.")


if __name__ == "__main__":
    main()
