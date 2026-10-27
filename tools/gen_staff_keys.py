#!/usr/bin/env python3
"""The organiser's key tool: the trust chain, shift certificates, staff QR codes and revocations.

Public keys go into the app (app/src/main/assets/staff_public.json). Secret seeds stay in tools/out/ (ignored by
git) and only travel inside staff QR codes. PROTOCOL.md, sections 9 and 11, describes every format written here.

  pip install pynacl "qrcode[pil]"

The chain (run once per step; each step keeps its seeds in tools/out/chain.json, except the root's):
  python tools/gen_staff_keys.py root --out /Volumes/STICK1/pingup-root.json /Volumes/STICK2/pingup-root.json
  python tools/gen_staff_keys.py organiser --name "Fest SRL" --root /Volumes/STICK1/pingup-root.json
The root seed goes only into those files, sealed with a passphrase; chain.json keeps its public key.
  python tools/gen_staff_keys.py event --name "Fest 2026" --start 2026-07-10 --days 3 --day-start 10:00
Rebuild the app after "root" and "event": they change staff_public.json.

Shift codes (SPS2) for one day of the event; leads and control also get that day's report key:
  python tools/gen_staff_keys.py issue --day 1 --from 10:00 --hours 12 \\
      --teams "Medical 1" "Securitate 1" --leads "Medical 2" --anchors main-stage bar --control Control
Revocation (SPR1, scanned by the control console, which sends it to every phone):
  python tools/gen_staff_keys.py revoke --ids 0123456789abcdef

The event pack (PROTOCOL.md, section 16): the venue, the first-aid sheets and the event's public keys in one file the
organiser signs, loaded by phones before the festival instead of a new build; it prints the gate code (SPE1) too:
  python tools/gen_staff_keys.py pack --id fest-2026 --version 1 --venue app/src/main/assets/venue.json \\
      --firstaid app/src/main/assets/firstaid --from 2026-06-20
An exercise has an event key and shared keys of its own, never the real event's; its pack says "exercise":
  python tools/gen_staff_keys.py event --exercise --name "Fest 2026 exercitiu" --start 2026-07-01
  python tools/gen_staff_keys.py issue --exercise --day 1 --from 10:00 --hours 4 --teams "Medical 1" --control Control
  python tools/gen_staff_keys.py pack --exercise --id fest-2026-exercitiu --version 1 --venue app/src/main/assets/venue.json

Legacy (SPS1): one shared key pair for the whole build, every team and anchor gets the same seeds:
  python tools/gen_staff_keys.py legacy --teams "Medical 1" --anchors main-stage --control Control [--new]
  python tools/gen_staff_keys.py demo     # a separate public pair for the debug build ("activate staff without QR")
"""

import argparse
import base64
import getpass
import hashlib
import json
import os
import re
import struct
import sys
from datetime import datetime, timedelta
from pathlib import Path

try:
    from nacl import bindings, pwhash, secret
    from nacl.exceptions import CryptoError
except ImportError:
    sys.exit('PyNaCl is missing: pip install pynacl "qrcode[pil]"')

from check_pack import plan_problems, schedule_problems
from check_venue import problems as venue_problems

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools" / "out"
SEEDS = OUT / "staff_seeds.json"
CHAIN = OUT / "chain.json"
PUBLIC = ROOT / "app" / "src" / "main" / "assets" / "staff_public.json"
DEMO = ROOT / "app" / "src" / "debug" / "assets" / "demo_staff.json"
DEMO_PUBLIC = ROOT / "app" / "src" / "debug" / "assets" / "staff_public.json"

ROLE_STAFF = 1
ROLE_ANCHOR = 2
ROLE_CONTROL = 3
ROLE_ORGANISER = 0x10
ROLE_EVENT = 0x11
ROLE_REPORT_KEY = 0x12
ROLE_NAMES = {ROLE_STAFF: "staff", ROLE_ANCHOR: "anchor", ROLE_CONTROL: "control"}
TEAM_BYTES = 24
ZONE_BYTES = 32
MAX_REVOKED = 32
HAS_REPORT_KEY, HAS_REPORT_SEED, HAS_LEGACY_SEED = 0x01, 0x02, 0x04
PACK_FORMAT = "pingup-event"
ROOT_FORMAT = "pingup-root"
# libsodium's cost for sensitive data, 1 GiB and about ten seconds: the root file is opened a few times a year
ROOT_KDF = (pwhash.argon2id.OPSLIMIT_SENSITIVE, pwhash.argon2id.MEMLIMIT_SENSITIVE)
MIN_PASSPHRASE = 16


def secret_dir() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    OUT.chmod(0o700)  # seeds and staff QR codes are secret


def b64(prefix: str, raw: bytes) -> str:
    return prefix + base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def name_bytes(text: str, limit: int, what: str) -> bytes:
    raw = text.encode("utf-8")
    if len(raw) > limit:
        sys.exit(f'{what} "{text}" is longer than {limit} bytes')
    return raw


def key_id(public: bytes) -> bytes:
    return hashlib.sha256(public).digest()[:8]


def sign_pair(seed: bytes) -> tuple[bytes, bytes]:
    return bindings.crypto_sign_seed_keypair(seed)


def certificate(role: int, key: bytes, start: int, end: int, name: str, issuer_seed: bytes) -> bytes:
    """Version 1: role, key, validity, issuer key id, name, then the issuer's Ed25519 signature over SP-CERT-v1 || all that."""
    issuer_public, issuer_secret = sign_pair(issuer_seed)
    raw = name_bytes(name, TEAM_BYTES, "Name")
    unsigned = bytes([1, role]) + key + struct.pack(">II", start, end) + key_id(issuer_public) + bytes([len(raw)]) + raw
    return unsigned + bindings.crypto_sign(b"SP-CERT-v1" + unsigned, issuer_secret)[:64]


def cert_id(cert: bytes) -> str:
    return hashlib.sha256(cert).digest()[:8].hex()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def save_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def update_public(**fields) -> None:
    """staff_public.json keeps the build's shared keys next to the chain, so SPS1 codes keep working meanwhile."""
    data = load_json(PUBLIC)
    data.update(fields)
    save_json(PUBLIC, data)
    print(f"public keys -> {PUBLIC.relative_to(ROOT)} (rebuild the app)")


def save_qr(text: str, path: Path) -> str:
    try:
        import qrcode
    except ImportError:
        return "(no image: pip install qrcode[pil])"
    qrcode.make(text, border=2, box_size=8).save(path)
    return str(path.relative_to(ROOT))


def chain_state(*needed: str) -> dict:
    state = load_json(CHAIN)
    for step in needed:
        if step not in state:
            sys.exit(f'Run the "{step}" step first.')
    return state


def root_key(passphrase: str, salt: bytes, ops: int, mem: int) -> bytes:
    return pwhash.argon2id.kdf(secret.SecretBox.KEY_SIZE, passphrase.encode("utf-8"), salt, opslimit=ops, memlimit=mem)


def seal_root(seed: bytes, passphrase: str) -> dict:
    """The root file: the seed in a secretbox whose key comes from the passphrase (Argon2id)."""
    ops, mem = ROOT_KDF
    salt = os.urandom(pwhash.argon2id.SALTBYTES)
    box = secret.SecretBox(root_key(passphrase, salt, ops, mem)).encrypt(seed)
    return {"format": ROOT_FORMAT, "version": 1, "public": sign_pair(seed)[0].hex(), "kdf": "argon2id13",
            "opslimit": ops, "memlimit": mem, "salt": salt.hex(), "box": bytes(box).hex(),
            "created": datetime.now().date().isoformat()}


def open_root(record: dict, passphrase: str) -> bytes:
    if record.get("format") != ROOT_FORMAT or record.get("version") != 1:
        sys.exit("Not a Ping Up root file.")
    key = root_key(passphrase, bytes.fromhex(record["salt"]), record["opslimit"], record["memlimit"])
    try:
        seed = secret.SecretBox(key).decrypt(bytes.fromhex(record["box"]))
    except CryptoError:
        sys.exit("Wrong passphrase for the root file.")
    if sign_pair(seed)[0].hex() != record["public"]:
        sys.exit("The root file is damaged: its seed doesn't give its public key.")
    return seed


def new_passphrase() -> str:
    first = getpass.getpass(f"Passphrase for the root file (at least {MIN_PASSPHRASE} characters, e.g. five random words): ")
    if len(first) < MIN_PASSPHRASE:
        sys.exit(f"Use at least {MIN_PASSPHRASE} characters.")
    if getpass.getpass("Again: ") != first:
        sys.exit("The two passphrases differ.")
    return first


def cmd_root(args) -> None:
    secret_dir()
    state = load_json(CHAIN)
    if "root" in state:
        sys.exit("A root key exists already; delete tools/out/chain.json to start a new chain.")
    paths = [Path(out) for out in args.out]
    taken = [str(path) for path in paths if path.exists()]
    if taken:
        sys.exit(f"A root file is never overwritten: {', '.join(taken)}")
    seed = os.urandom(32)
    passphrase = new_passphrase()
    print("sealing the seed (about ten seconds)")
    text = json.dumps(seal_root(seed, passphrase), indent=2) + "\n"
    for path in paths:
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"root file -> {path}")
    public = sign_pair(seed)[0]
    state["root"] = {"public": public.hex()}
    save_json(CHAIN, state)
    update_public(root=public.hex())
    print(f"root key id {key_id(public).hex()}")


def root_seed(state: dict, path: str | None) -> bytes:
    """Chains made before the root file (exercises, the demo) keep the seed in chain.json; the others need the stick."""
    if "seed" in state["root"]:
        return bytes.fromhex(state["root"]["seed"])
    if not path:
        sys.exit("The root key is offline: give its file, --root /Volumes/<stick>/pingup-root.json")
    if not Path(path).exists():
        sys.exit(f"{path} doesn't exist.")
    record = load_json(Path(path))
    if record.get("public") != state["root"]["public"]:
        sys.exit(f"{path} holds another root key than this chain's.")
    passphrase = getpass.getpass("Passphrase of the root file: ")
    print("opening the root file (about ten seconds)")
    return open_root(record, passphrase)


def cmd_organiser(args) -> None:
    state = chain_state("root")
    seed = os.urandom(32)
    now = int(datetime.now().timestamp())
    cert = certificate(ROLE_ORGANISER, sign_pair(seed)[0], now, now + args.years * 366 * 86400, args.name,
                       root_seed(state, args.root))
    state["organiser"] = {"seed": seed.hex(), "cert": cert.hex()}
    save_json(CHAIN, state)
    print(f"organiser {args.name}: certificate {cert_id(cert)}")


def day_bounds(start: str, day_start: str, days: int) -> list[tuple[int, int]]:
    first = datetime.fromisoformat(f"{start}T{day_start}")
    return [(int((first + timedelta(days=i)).timestamp()), int((first + timedelta(days=i + 1)).timestamp()))
            for i in range(days)]


def event_step(args) -> str:
    """Where chain.json keeps the event: a real one under "event", an exercise under "exercise"."""
    return "exercise" if args.exercise else "event"


def cmd_event(args) -> None:
    state = chain_state("root", "organiser")
    step = event_step(args)
    seed = os.urandom(32)
    days = day_bounds(args.start, args.day_start, args.days)
    cert = certificate(ROLE_EVENT, sign_pair(seed)[0], days[0][0] - 86400, days[-1][1] + 86400, args.name,
                       bytes.fromhex(state["organiser"]["seed"]))
    report_days = []
    for start, end in days:
        day_seed = os.urandom(32)
        public, _ = bindings.crypto_box_seed_keypair(day_seed)
        report_days.append({"seed": day_seed.hex(), "cert": certificate(ROLE_REPORT_KEY, public, start, end, "", seed).hex()})
    state[step] = {"seed": seed.hex(), "cert": cert.hex(), "days": report_days, "name": args.name}
    if args.exercise:
        # its own shared pair, so nothing sealed or signed in the exercise opens or counts in the real event
        state[step]["boxSeed"], state[step]["signSeed"] = os.urandom(32).hex(), os.urandom(32).hex()
    else:
        update_public(chain=[state["organiser"]["cert"], cert.hex()], reportKeys=[d["cert"] for d in report_days])
    save_json(CHAIN, state)
    print(f"{step} {args.name}: certificate {cert_id(cert)}, {len(days)} days of report keys")


def shift_payload(cert: bytes, seed: bytes, report: dict | None, with_seed: bool, legacy: bytes | None, zone: str) -> str:
    flags = (HAS_REPORT_KEY if report else 0) | (HAS_REPORT_SEED if report and with_seed else 0) | (HAS_LEGACY_SEED if legacy else 0)
    raw = cert + seed + bytes([flags])
    if report:
        raw += bytes.fromhex(report["cert"])
        if with_seed:
            raw += bytes.fromhex(report["seed"])
    if legacy:
        raw += legacy
    zone_b = name_bytes(zone, ZONE_BYTES, "Zone")
    return b64("SPS2.", raw + bytes([len(zone_b)]) + zone_b)


def cmd_issue(args) -> None:
    step = event_step(args)
    state = chain_state(step)
    days = state[step]["days"]
    if not 1 <= args.day <= len(days):
        sys.exit(f"The event has days 1 to {len(days)}.")
    report = days[args.day - 1]
    day_date = datetime.fromtimestamp(cert_start(report["cert"])).date()
    start = int(datetime.fromisoformat(f"{day_date.isoformat()}T{args.from_time}").timestamp())
    end = start + int(args.hours * 3600)
    event_seed = bytes.fromhex(state[step]["seed"])
    legacy = None
    if args.legacy:
        legacy = bytes.fromhex(state[step]["boxSeed"] if args.exercise else load_json(SEEDS)["boxSeed"])
    prefix = "exercise_" if args.exercise else ""

    cards = [(ROLE_STAFF, team, "", False) for team in args.teams]
    cards += [(ROLE_STAFF, team, "", True) for team in args.leads]
    cards += [(ROLE_ANCHOR, f"Ancora {zone}"[:TEAM_BYTES], zone, False) for zone in args.anchors]
    cards += [(ROLE_CONTROL, name, "", True) for name in args.control]
    lines = []
    for role, team, zone, opens in cards:
        seed = os.urandom(32)
        cert = certificate(role, sign_pair(seed)[0], start, end, team, event_seed)
        # an anchor sits unattended: it proves its role and opens nothing
        text = shift_payload(cert, seed, report if role != ROLE_ANCHOR else None, opens, legacy if opens else None, zone)
        name = re.sub(r"[^A-Za-z0-9]+", "_", team).strip("_").lower()
        image = save_qr(text, OUT / f"{prefix}day{args.day}_{ROLE_NAMES[role]}_{name}.png")
        lines.append(f"{cert_id(cert)}\t{ROLE_NAMES[role]}\t{team}\t{text}")
        print(f"{ROLE_NAMES[role]}: {team} (certificate {cert_id(cert)}) -> {image}")
    with (OUT / f"{prefix}day{args.day}_qr.txt").open("a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"certificate ids and QR contents -> {(OUT / f'{prefix}day{args.day}_qr.txt').relative_to(ROOT)}")


def cert_start(cert_hex: str) -> int:
    return struct.unpack(">I", bytes.fromhex(cert_hex)[34:38])[0]


def cmd_revoke(args) -> None:
    state = chain_state(args.by)
    ids = [bytes.fromhex(i) for i in args.ids]
    if not 1 <= len(ids) <= MAX_REVOKED or any(len(i) != 8 for i in ids):
        sys.exit(f"Give 1 to {MAX_REVOKED} certificate ids of 16 hex digits.")
    seed = bytes.fromhex(state[args.by]["seed"])
    public, secret = sign_pair(seed)
    unsigned = bytes([1]) + key_id(public) + struct.pack(">IB", int(datetime.now().timestamp()), len(ids)) + b"".join(ids)
    text = b64("SPR1.", unsigned + bindings.crypto_sign(b"SP-ANN-v1" + unsigned, secret)[:64])
    secret_dir()
    print(text)
    print(f"QR -> {save_qr(text, OUT / 'revocation.png')}; scan it with the control console.")


def cert_end(cert_hex: str) -> int:
    return struct.unpack(">I", bytes.fromhex(cert_hex)[38:42])[0]


def pack_body(args, state: dict) -> str:
    step = event_step(args)
    event = state[step]
    start = int(datetime.fromisoformat(args.from_date).timestamp()) if args.from_date else int(datetime.now().timestamp())
    body = {
        "id": args.id,
        "version": args.version,
        "kind": "exercise" if args.exercise else "real",
        "name": args.name or event.get("name", args.id),
        "validFrom": start,
        "validUntil": cert_end(event["cert"]),
        "organiser": state["organiser"]["cert"],
        "event": event["cert"],
        "reportKeys": [d["cert"] for d in event["days"]],
    }
    if args.exercise:
        body["box"] = bindings.crypto_box_seed_keypair(bytes.fromhex(event["boxSeed"]))[0].hex()
        body["sign"] = sign_pair(bytes.fromhex(event["signSeed"]))[0].hex()
    body["venue"] = json.loads(Path(args.venue).read_text(encoding="utf-8"))
    body["grid"] = {"cellMeters": args.grid}
    if args.firstaid:
        body["firstAid"] = {f.name: f.read_text(encoding="utf-8") for f in sorted(Path(args.firstaid).glob("*.md"))}
    if args.schedule:
        body["schedule"] = json.loads(Path(args.schedule).read_text(encoding="utf-8"))
    if args.plan:
        body["plan"] = json.loads(Path(args.plan).read_text(encoding="utf-8"))
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


def cmd_pack(args) -> None:
    """The organiser signs SP-PACK-v1 || the body; phones check that and the chain up to the root in the app."""
    # a signed pack can't be fixed on the phones, so a broken map stops here
    venue = json.loads(Path(args.venue).read_text(encoding="utf-8"))
    found = venue_problems(venue)
    if found:
        sys.exit(f"{args.venue}:\n" + "\n".join(found))
    # the same for the schedule and the plan: phones drop a pack whose schedule or plan they can't read
    for path, check in ((args.schedule, lambda data: schedule_problems(data, venue)), (args.plan, plan_problems)):
        found = check(json.loads(Path(path).read_text(encoding="utf-8"))) if path else []
        if found:
            sys.exit(f"{path}:\n" + "\n".join(found))
    state = chain_state("organiser", event_step(args))
    body = pack_body(args, state)
    _, organiser_secret = sign_pair(bytes.fromhex(state["organiser"]["seed"]))
    signature = bindings.crypto_sign(b"SP-PACK-v1" + body.encode("utf-8"), organiser_secret)[:64]
    text = json.dumps({"format": PACK_FORMAT, "version": 1, "body": body, "signature": signature.hex()}, ensure_ascii=False)
    secret_dir()
    name = f"{args.id}-v{args.version}"
    path = Path(args.out) if args.out else OUT / f"{name}.pingup.json"
    path.write_text(text, encoding="utf-8")
    gate = b64("SPE1.", hashlib.sha256(body.encode("utf-8")).digest())
    print(f"pack -> {path} ({len(text.encode('utf-8'))} bytes); publish it before the event")
    print(f"gate code {gate} -> {save_qr(gate, OUT / f'{name}_gate.png')}")


def cmd_legacy(args) -> None:
    if SEEDS.exists() and not args.new:
        data = load_json(SEEDS)
        box_seed, sign_seed = bytes.fromhex(data["boxSeed"]), bytes.fromhex(data["signSeed"])
    else:
        secret_dir()
        box_seed, sign_seed = os.urandom(32), os.urandom(32)
        save_json(SEEDS, {"boxSeed": box_seed.hex(), "signSeed": sign_seed.hex()})
    update_public(box=bindings.crypto_box_seed_keypair(box_seed)[0].hex(), sign=sign_pair(sign_seed)[0].hex())

    cards = [(ROLE_STAFF, team, "") for team in args.teams]
    cards += [(ROLE_ANCHOR, f"Ancora {zone}"[:TEAM_BYTES], zone) for zone in args.anchors]
    cards += [(ROLE_CONTROL, name, "") for name in args.control]
    lines = []
    for role, team, zone in cards:
        raw = box_seed + sign_seed + bytes([role])
        team_b, zone_b = name_bytes(team, TEAM_BYTES, "Team name"), name_bytes(zone, ZONE_BYTES, "Zone")
        text = b64("SPS1.", raw + bytes([len(team_b)]) + team_b + bytes([len(zone_b)]) + zone_b)
        name = re.sub(r"[^A-Za-z0-9]+", "_", team).strip("_").lower()
        lines.append(f"{team}\t{text}")
        print(f"{ROLE_NAMES[role]}: {team} -> {save_qr(text, OUT / f'{ROLE_NAMES[role]}_{name}.png')}")
    (OUT / "staff_qr.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"QR contents -> {(OUT / 'staff_qr.txt').relative_to(ROOT)}")


def cmd_demo(_args) -> None:
    """A separate pair for the debug build: its public key overrides the one from main in debug."""
    box_seed, sign_seed = os.urandom(32), os.urandom(32)
    # the demo chain's root and certificates (debug assets) stay as they are
    data = load_json(DEMO_PUBLIC)
    data.update(box=bindings.crypto_box_seed_keypair(box_seed)[0].hex(), sign=sign_pair(sign_seed)[0].hex())
    save_json(DEMO_PUBLIC, data)
    save_json(DEMO, {"boxSeed": box_seed.hex(), "signSeed": sign_seed.hex()})
    print(f"demo keys -> {DEMO_PUBLIC.relative_to(ROOT)}, {DEMO.relative_to(ROOT)} (debug build only)")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("root", help="Ping Up's root key, sealed with a passphrase")
    p.add_argument("--out", nargs="+", required=True, help="the root files, one on each stick, never on the laptop")
    p = sub.add_parser("organiser", help="an organiser key, signed by the root")
    p.add_argument("--name", required=True)
    p.add_argument("--years", type=int, default=1)
    p.add_argument("--root", help="the root file (on its stick)")
    p = sub.add_parser("event", help="the event key and one report key per day, signed by the organiser")
    p.add_argument("--name", required=True)
    p.add_argument("--start", required=True, help="first day, YYYY-MM-DD")
    p.add_argument("--days", type=int, default=1)
    p.add_argument("--day-start", default="10:00", help="when each day's report key takes over, HH:MM local time")
    p.add_argument("--exercise", action="store_true", help="an exercise, with keys of its own")
    p = sub.add_parser("issue", help="shift certificates and SPS2 codes for one day")
    p.add_argument("--day", type=int, required=True)
    p.add_argument("--from", dest="from_time", required=True, help="shift start, HH:MM local time")
    p.add_argument("--hours", type=float, required=True)
    p.add_argument("--teams", nargs="*", default=[], help="teams that relay reports and sign their own ACKs")
    p.add_argument("--leads", nargs="*", default=[], help="teams that also open reports (the day's report key)")
    p.add_argument("--anchors", nargs="*", default=[], help="venue.json zone ids")
    p.add_argument("--control", nargs="*", default=[], help="control-room consoles; they open reports")
    p.add_argument("--legacy", action="store_true", help="leads and control also open reports sealed to the build's key")
    p.add_argument("--exercise", action="store_true", help="shifts of the exercise")
    p = sub.add_parser("revoke", help="an SPR1 revocation of certificate ids")
    p.add_argument("--ids", nargs="+", required=True)
    p.add_argument("--by", choices=["event", "exercise", "organiser"], default="event")
    p = sub.add_parser("pack", help="the signed event pack and its gate code")
    p.add_argument("--id", required=True, help="the same id for every version of one event")
    p.add_argument("--version", type=int, required=True, help="a phone replaces a pack only with a higher version")
    p.add_argument("--venue", required=True, help="the venue JSON (as app/src/main/assets/venue.json)")
    p.add_argument("--firstaid", help="a folder of first-aid sheets (*.md), approved by the medical lead")
    p.add_argument("--schedule", help='the sets on the stages, a JSON list of {"stage", "title", "start", "end"}')
    p.add_argument("--plan", help="the site plan with its corners, as tools/plan_corners.py writes it")
    p.add_argument("--name", help="the name phones show; the event's by default")
    p.add_argument("--from", dest="from_date", help="first day phones may load it, YYYY-MM-DD; today by default")
    p.add_argument("--grid", type=int, default=50, help="site grid cell, meters")
    p.add_argument("--exercise", action="store_true", help="the exercise's pack")
    p.add_argument("--out", help="where to write it; tools/out/<id>-v<version>.pingup.json by default")
    p = sub.add_parser("legacy", help="SPS1 codes with the build's shared keys")
    p.add_argument("--teams", nargs="*", default=["Medical 1", "Securitate 1"])
    p.add_argument("--anchors", nargs="*", default=[])
    p.add_argument("--control", nargs="*", default=[])
    p.add_argument("--new", action="store_true", help="new shared keys (old SPS1 codes stop working)")
    sub.add_parser("demo", help="the debug build's shared pair")
    args = parser.parse_args(argv)
    {"root": cmd_root, "organiser": cmd_organiser, "event": cmd_event, "issue": cmd_issue, "revoke": cmd_revoke,
     "pack": cmd_pack, "legacy": cmd_legacy, "demo": cmd_demo}[args.command](args)


if __name__ == "__main__":
    main()
