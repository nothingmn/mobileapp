#!/usr/bin/env python3
"""Generate the Notebook's capture chime.

One soft, glassy two-tone tap: an 880 Hz sine with its fifth above at 1320 Hz, a few milliseconds of attack so it
does not click, and an exponential decay to silence in about a fifth of a second, peaked well below full scale so
it sits under speech and music instead of over them. Nothing here is borrowed from the phone: the built-in
notification sounds differ per handset and some are harsh, so the sample is made, committed beside this script and
rebuilt by running it.

    scripts/make-capture-chime.py                # rewrite the resource the app ships
    scripts/make-capture-chime.py --out x.wav    # somewhere else, for listening
    scripts/make-capture-chime.py --print        # print the specification, write nothing

Standard library only, so anyone can reproduce the file without installing anything.
"""

from __future__ import annotations

import argparse
import math
import pathlib
import struct
import wave

RATE = 44_100  # SoundPool plays at the device rate; 44.1 kHz needs no resampling on any Android phone.
DURATION_MS = 200
ATTACK_MS = 4.0
DECAY_TAU_MS = 55.0  # Exponential time constant: -8.7 dB by 100 ms, and the tail is gone before the cut.
RELEASE_MS = 8.0  # Linear fade over the last few milliseconds, so the end is a silence and not a step.
PARTIALS = ((880.0, 1.0), (1320.0, 0.55))  # A5, and E6 a perfect fifth above it.
PEAK = 0.25  # -12 dBFS: audible under speech and music, startling to nobody.

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "notebook/src/androidMain/res/raw/capture_chime.wav"


def sample_at(t: float) -> float:
    """The tone at [t] seconds, before the peak scaling."""
    return sum(weight * math.sin(2.0 * math.pi * freq * t) for freq, weight in PARTIALS)


def envelope_at(ms: float) -> float:
    """Attack, exponential decay, and the release that ends the sound in silence."""
    attack = min(1.0, ms / ATTACK_MS)
    attack = 0.5 - 0.5 * math.cos(math.pi * attack)  # raised cosine: no click at the start
    decay = math.exp(-ms / DECAY_TAU_MS)
    release = min(1.0, (DURATION_MS - ms) / RELEASE_MS)
    return attack * decay * release


def render() -> list[float]:
    count = RATE * DURATION_MS // 1000
    raw = [sample_at(i / RATE) * envelope_at(1000.0 * i / RATE) for i in range(count)]
    loudest = max(abs(value) for value in raw)
    scale = PEAK / loudest
    return [value * scale for value in raw]


def write(path: pathlib.Path) -> None:
    frames = b"".join(struct.pack("<h", int(round(value * 32767))) for value in render())
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(frames)


def describe() -> str:
    return (
        f"{DURATION_MS} ms, {RATE} Hz mono 16-bit, two sine partials at "
        f"{', '.join(f'{freq:.0f} Hz' for freq, _ in PARTIALS)}, {ATTACK_MS:.0f} ms attack, "
        f"exponential decay (tau {DECAY_TAU_MS:.0f} ms), peak {PEAK:.2f} full scale "
        f"({20.0 * math.log10(PEAK):.1f} dBFS)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the Notebook's capture chime.")
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT, help=f"where to write it (default {DEFAULT_OUT})")
    parser.add_argument("--print", action="store_true", help="print the specification and write nothing")
    args = parser.parse_args()

    print(describe())
    if args.print:
        return
    write(args.out)
    print(f"wrote {args.out} ({args.out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
