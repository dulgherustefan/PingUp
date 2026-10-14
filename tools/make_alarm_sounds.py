#!/usr/bin/env python3
"""Makes the iPhone app's alarm sounds, the Core Audio files iOS plays with a notification.

  python3 tools/make_alarm_sounds.py           # writes them to iosApp/Sounds
  python3 tools/make_alarm_sounds.py --check   # fails if the files there aren't what it makes

iOS plays a notification's own sound only if it lasts under 30 seconds, otherwise its default one
(UNNotificationSound). The tones are made here from sine waves, nothing recorded or downloaded, so they
are the project's own (REUSE.toml):

- pingup_evacuation.caf: the evacuation signal of ISO 8201, the "temporal three": three half-second
  tones and a pause of a second and a half, three times.
- pingup_pause.caf: a falling two-note chime, four times: the show stops, nobody runs.
- pingup_alarm.caf: quick triple beeps, for an insistent staff alert, which rings again every minute.
- pingup_silence.caf: a second of silence, for an alert meant to be felt: the phone only vibrates.

The check allows each sample to differ by a little, as the sine of one machine's maths library may round
differently from another's.
"""

import argparse
import math
import struct
import sys
from array import array
from pathlib import Path

RATE = 22050
OUT = Path(__file__).resolve().parent.parent / "iosApp" / "Sounds"
LOUDNESS = 0.85
RAMP_S = 0.01
TOLERANCE = 2


def tone(freq, seconds, decay=0.0):
    """A tone with a little of its third harmonic, so a phone's small speaker carries it, ramped so it doesn't click."""
    count = int(RATE * seconds)
    ramp = int(RATE * RAMP_S)
    out = []
    for i in range(count):
        t = i / RATE
        wave = (math.sin(2 * math.pi * freq * t) + 0.3 * math.sin(2 * math.pi * 3 * freq * t)) / 1.3
        envelope = min(1.0, i / ramp, (count - i) / ramp) * math.exp(-decay * t)
        out.append(wave * envelope)
    return out


def silence(seconds):
    return [0.0] * int(RATE * seconds)


def evacuation():
    beep = tone(1000, 0.5)
    cycle = beep + silence(0.5) + beep + silence(0.5) + beep + silence(1.5)
    return cycle * 3


def pause():
    chime = tone(784, 0.45, decay=3.0) + tone(523, 0.9, decay=3.0) + silence(0.45)
    return chime * 4


def alarm():
    beep = tone(1400, 0.12)
    cycle = beep + silence(0.08) + beep + silence(0.08) + beep + silence(0.6)
    return cycle * 6


SOUNDS = {
    "pingup_evacuation.caf": evacuation,
    "pingup_pause.caf": pause,
    "pingup_alarm.caf": alarm,
    "pingup_silence.caf": lambda: silence(1.0),
}


def pcm(signal):
    samples = array("h", (int(round(s * LOUDNESS * 32767)) for s in signal))
    if sys.byteorder == "big":
        samples.byteswap()
    return samples.tobytes()


def caf(data):
    """16-bit mono linear PCM, little-endian, in Apple's Core Audio Format: the header, its description, the data."""
    header = b"caff" + struct.pack(">HH", 1, 0)
    # sample rate, format, flags (little-endian), bytes per packet, frames per packet, channels, bits per channel
    desc = b"desc" + struct.pack(">q", 32) + struct.pack(">d4sIIIII", float(RATE), b"lpcm", 2, 2, 1, 1, 16)
    audio = b"data" + struct.pack(">q", 4 + len(data)) + struct.pack(">I", 0) + data
    return header + desc + audio


HEADER_SIZE = len(caf(b""))


def same(made, kept):
    """The same file, but for samples a rounding apart."""
    if len(made) != len(kept) or made[:HEADER_SIZE] != kept[:HEADER_SIZE]:
        return False
    a = array("h", made[HEADER_SIZE:])
    b = array("h", kept[HEADER_SIZE:])
    return all(abs(x - y) <= TOLERANCE for x, y in zip(a, b))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only check the files in iosApp/Sounds")
    args = parser.parse_args()
    wrong = []
    for name, make in SOUNDS.items():
        signal = make()
        seconds = len(signal) / RATE
        assert seconds < 30, f"{name} lasts {seconds:.1f} s: iOS would play its default sound"
        made = caf(pcm(signal))
        path = OUT / name
        if args.check:
            if not path.exists() or not same(made, path.read_bytes()):
                wrong.append(name)
        else:
            OUT.mkdir(parents=True, exist_ok=True)
            path.write_bytes(made)
            print(f"{path.relative_to(OUT.parent.parent)}: {seconds:.1f} s, {len(made) // 1024} KiB")
    if wrong:
        print("not what tools/make_alarm_sounds.py makes: " + ", ".join(wrong), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
