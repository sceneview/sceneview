#!/usr/bin/env python3
"""Generate the Sound stage loops; script and generated audio dedicated to CC0.

Python 3 standard library only. Independent A-minor figures intentionally drift:
MediaPlayer loops are neither gapless nor synchronised. All notes finish before
an 80 ms silent tail; every file starts with 80 ms of silence.
"""

import math
from pathlib import Path
import random
import struct
import wave

RATE = 22_050
PEAK = 0.48  # -6.38 dBFS, with headroom for the three-source mix.
SILENCE = 0.08
ROOT = Path(__file__).resolve().parents[2]
DESTINATIONS = (ROOT / "assets/audio", ROOT / "samples/android-demo/src/main/assets/audio")


def note(samples, start, duration, frequency, kind, rng):
    """Add a fully windowed voice; all harmonics stay below Nyquist."""
    count = round(duration * RATE)
    offset = round(start * RATE)
    phase = rng.uniform(0.0, math.tau)
    for i in range(count):
        t = i / RATE
        attack = min(1.0, t / 0.012)
        release = min(1.0, (count - 1 - i) / (0.06 * RATE))
        envelope = math.sin(attack * math.pi / 2) ** 2 * release ** 2
        angle = math.tau * frequency * t
        if kind == "low":
            voice = math.sin(angle) + 0.15 * math.sin(2 * angle)
            envelope *= math.sin(math.pi * i / (count - 1)) ** 2
        elif kind == "mid":
            voice = math.sin(angle) + 0.32 * math.exp(-10 * t) * math.sin(3 * angle)
            envelope *= math.exp(-6 * t)
        else:
            voice = (math.sin(angle) + 0.24 * math.sin(2 * angle + phase)
                     + 0.12 * math.sin(3 * angle))
            envelope *= math.exp(-4 * t) * (0.85 + 0.15 * math.cos(math.tau * 5 * t))
        samples[offset + i] += voice * envelope


def generate(name, duration, notes):
    samples = [0.0] * round(duration * RATE)
    rng = random.Random(20261007)
    for start, length, frequency in notes:
        assert start >= SILENCE and start + length <= duration - SILENCE
        note(samples, start, length, frequency, name, rng)
    factor = PEAK / max(abs(value) for value in samples)
    pcm = [round(value * factor * 32767) for value in samples]
    quiet = round(SILENCE * RATE)
    assert all(value == 0 for value in pcm[:quiet] + pcm[-quiet:])
    data = struct.pack(f"<{len(pcm)}h", *pcm)
    filename = f"stage_{name}.wav"
    for directory in DESTINATIONS:
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / filename
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(RATE)
            wav.writeframes(data)
        assert path.stat().st_size < 160_000
    peak_db = 20 * math.log10(max(abs(value) for value in pcm) / 32768)
    print(f"{filename}: {duration:.1f} s, {len(data) + 44} bytes, "
          f"{RATE} Hz mono PCM16, peak {peak_db:.2f} dBFS, "
          "80 ms silent edges; wrote canonical + Android copies")


def main():
    generate("low", 2.0, [(0.08, 0.82, 110.0), (1.06, 0.78, 164.813778)])
    generate("mid", 2.6, [(0.12, 0.58, 440.0), (0.93, 0.56, 523.251131),
                          (1.79, 0.65, 659.255114)])
    generate("high", 3.4, [(0.08, 0.85, 1318.510228), (1.24, 0.72, 1760.0),
                           (2.28, 0.96, 2093.004522)])


if __name__ == "__main__":
    main()
