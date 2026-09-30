#!/usr/bin/env python3
"""Synthesize the four looping stems of the AR Sound Garden demo.

The four files are the four parts of ONE short piece — rhythm, bass, pad and bells —
rendered to the exact same length so the demo can mix them sample-for-sample: every
orb in the garden plays one part, and walking between the orbs remixes the piece.

Everything is procedural (sine / noise synthesis, fixed RNG seed): no sample, no
third-party recording, so the output is the SceneView project's own work, dedicated
to the public domain (CC0-1.0) like `bell.wav`.

    A minor, 90 BPM, 4 bars of 4/4 = 16 beats = 10.667 s = 512 000 frames @ 48 kHz
    Am7 | Fmaj7 | Cmaj7 | G

Every note is written into a circular buffer, so a release tail that runs past the
last bar wraps onto the first one: the loop point is seamless by construction.

Requirements: numpy + soundfile (libsndfile ships libvorbis in the wheel). ffmpeg's
native Vorbis encoder is stereo-only, hence soundfile:

    python3 -m venv /tmp/sg && /tmp/sg/bin/pip install numpy soundfile
    /tmp/sg/bin/python tools/generate-sound-garden-stems.py \\
        samples/android-demo/src/main/assets/audio \\
        samples/ios-demo/SceneViewDemo/Audio

Writes `garden_beat.ogg`, `garden_bass.ogg`, `garden_pad.ogg`, `garden_bells.ogg`
(mono Ogg Vorbis, 48 kHz) into the first directory. With a second directory (macOS only),
also writes the same four parts as `.caf` for iOS: AAC encoded by Apple's `afconvert`,
whose CAF packet table records the encoder priming and remainder, so Core Audio decodes
exactly 512 000 frames and the loop stays gapless (iOS has no Vorbis decoder). The Kotlin
side pins the same loop length in `SoundGardenStems.LOOP_FRAMES` and the Swift side in
`SoundGardenStems.loopFrames` — change BPM or bar count in all three places.
"""
import math
import os
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf

SR = 48_000
BPM = 90
BEAT = SR * 60 // BPM          # 32 000 frames
BARS = 4
LOOP = BARS * 4 * BEAT         # 512 000 frames
RNG = np.random.default_rng(20260930)

# One chord per bar.
BASS_ROOTS = [45, 41, 48, 43]                       # A2 F2 C3 G2
PAD_VOICINGS = [
    [57, 60, 64, 67],                               # Am7   A3 C4 E4 G4
    [57, 60, 64, 65],                               # Fmaj7 A3 C4 E4 F4
    [55, 59, 64, 67],                               # Cmaj7 G3 B3 E4 G4
    [55, 59, 62, 67],                               # G     G3 B3 D4 G4
]
BELL_TONES = [
    [81, 84, 88, 91],                               # A5 C6 E6 G6
    [81, 84, 89, 88],                               # A5 C6 F6 E6
    [79, 84, 88, 91],                               # G5 C6 E6 G6
    [79, 83, 86, 91],                               # G5 B5 D6 G6
]
# Index into the bar's BELL_TONES per eighth note; None is a rest.
BELL_PATTERNS = [
    [0, None, 1, 2, None, 3, 2, 1],
    [0, None, 1, 2, None, 3, 2, None],
    [0, None, 1, 2, None, 3, 2, 1],
    [0, 1, 2, 3, 2, None, 1, None],
]


def hz(midi: float) -> float:
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def add(buf: np.ndarray, start: int, sig: np.ndarray) -> None:
    """Adds `sig` at `start` into the circular `buf` (tails wrap to the top)."""
    start %= LOOP
    n = len(sig)
    first = min(n, LOOP - start)
    buf[start:start + first] += sig[:first]
    rest = sig[first:]
    while len(rest):
        take = min(len(rest), LOOP)
        buf[:take] += rest[:take]
        rest = rest[take:]


def t_axis(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * SR)) / SR


def ramp_in(sig: np.ndarray, seconds: float) -> np.ndarray:
    n = min(len(sig), max(1, int(seconds * SR)))
    sig[:n] *= np.linspace(0.0, 1.0, n)
    return sig


def one_pole_lowpass(x: np.ndarray, cutoff_hz: float) -> np.ndarray:
    a = 1.0 - math.exp(-2.0 * math.pi * cutoff_hz / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc += a * (v - acc)
        y[i] = acc
    return y


# ─── Rhythm ────────────────────────────────────────────────────────────────────
def kick() -> np.ndarray:
    t = t_axis(0.5)
    freq = 46.0 + 95.0 * np.exp(-t / 0.035)
    phase = 2.0 * np.pi * np.cumsum(freq) / SR
    body = np.sin(phase) * np.exp(-t / 0.26)
    click = RNG.standard_normal(len(t)) * np.exp(-t / 0.002) * 0.25
    return ramp_in(body + click, 0.001)


def snap() -> np.ndarray:
    """Soft snare / clap: band-limited noise plus a short 185 Hz body."""
    t = t_axis(0.35)
    noise = RNG.standard_normal(len(t))
    noise = np.diff(noise, prepend=0.0)             # tilt up
    noise = one_pole_lowpass(noise, 4_000.0)
    tone = np.sin(2.0 * np.pi * 185.0 * t) * np.exp(-t / 0.045)
    return ramp_in(noise * np.exp(-t / 0.055) * 0.4 + tone * 0.5, 0.001)


def hat(decay: float) -> np.ndarray:
    t = t_axis(0.15)
    noise = RNG.standard_normal(len(t))
    noise = np.diff(np.diff(noise, prepend=0.0), prepend=0.0)  # crude high-pass
    return ramp_in(noise * np.exp(-t / decay) * 0.25, 0.0005)


def render_beat() -> np.ndarray:
    buf = np.zeros(LOOP)
    swing = 0.06                                     # offbeat eighths land late
    for bar in range(BARS):
        b0 = bar * 4 * BEAT
        kicks = [0.0, 2.5] if bar % 2 == 0 else [0.0, 1.5, 2.5]
        for k in kicks:
            add(buf, b0 + int(k * BEAT), kick())
        for s in (1.0, 3.0):
            add(buf, b0 + int(s * BEAT), snap())
        for e in range(8):
            pos = e * 0.5 + (swing if e % 2 else 0.0)
            level = 1.0 if e % 2 else 0.55
            add(buf, b0 + int(pos * BEAT), hat(0.028) * level)
        for s16 in range(16):                        # ghost shaker
            if s16 % 2:
                add(buf, b0 + int((s16 * 0.25 + swing / 2) * BEAT), hat(0.012) * 0.22)
    return buf


# ─── Bass ──────────────────────────────────────────────────────────────────────
def bass_note(midi: int, beats: float, velocity: float) -> np.ndarray:
    dur = beats * BEAT / SR
    t = t_axis(dur + 0.08)
    f = hz(midi)
    ph = 2.0 * np.pi * f * t
    # Harmonics so the part still reads on a phone speaker, where a pure sub-sine vanishes.
    wave = np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.22 * np.sin(3 * ph) + 0.08 * np.sin(4 * ph)
    wave = np.tanh(1.4 * wave)
    env = np.exp(-t / (dur * 1.6))
    release_at = int(dur * SR)
    env[release_at:] *= np.linspace(1.0, 0.0, len(env) - release_at)
    return ramp_in(wave * env * velocity, 0.008)


def render_bass() -> np.ndarray:
    buf = np.zeros(LOOP)
    rhythm = [(0.0, 1.35, 1.0), (1.5, 0.4, 0.7), (2.0, 1.3, 0.9), (3.5, 0.42, 0.75)]
    for bar, root in enumerate(BASS_ROOTS):
        b0 = bar * 4 * BEAT
        for i, (start, beats, vel) in enumerate(rhythm):
            note = root + 12 if i == 3 else root     # octave pickup into the next bar
            add(buf, b0 + int(start * BEAT), bass_note(note, beats, vel))
    return buf


# ─── Pad ───────────────────────────────────────────────────────────────────────
def pad_chord(notes: list[int], bar_seconds: float) -> np.ndarray:
    attack, release = 1.1, 1.2
    t = t_axis(bar_seconds + release)
    out = np.zeros(len(t))
    for midi in notes:
        for cents in (-6.0, 5.0):
            f = hz(midi + cents / 100.0)
            ph = 2.0 * np.pi * f * t + RNG.uniform(0, 2 * np.pi)
            out += np.sin(ph) + 0.22 * np.sin(2 * ph) + 0.07 * np.sin(3 * ph)
    env = np.ones(len(t))
    a = int(attack * SR)
    env[:a] = 0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, a))
    r0 = int(bar_seconds * SR)
    env[r0:] = 0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, len(t) - r0))
    tremolo = 1.0 + 0.12 * np.sin(2.0 * np.pi * 0.375 * t)   # one swell per 2 beats
    return out * env * tremolo


def render_pad() -> np.ndarray:
    buf = np.zeros(LOOP)
    bar_seconds = 4 * BEAT / SR
    for bar, notes in enumerate(PAD_VOICINGS):
        add(buf, bar * 4 * BEAT, pad_chord(notes, bar_seconds))
    return buf


# ─── Bells ─────────────────────────────────────────────────────────────────────
def bell(midi: int, velocity: float) -> np.ndarray:
    """Kalimba-like: a decaying fundamental plus two quick inharmonic partials."""
    t = t_axis(1.8)
    f = hz(midi)
    tone = (
        np.sin(2 * np.pi * f * t) * np.exp(-t / 0.75)
        + 0.30 * np.sin(2 * np.pi * 4.0 * f * t) * np.exp(-t / 0.10)
        + 0.14 * np.sin(2 * np.pi * 2.76 * f * t) * np.exp(-t / 0.25)
    )
    return ramp_in(tone * velocity, 0.003)


def render_bells() -> np.ndarray:
    buf = np.zeros(LOOP)
    for bar in range(BARS):
        b0 = bar * 4 * BEAT
        for e, idx in enumerate(BELL_PATTERNS[bar]):
            if idx is None:
                continue
            velocity = 1.0 if e % 2 == 0 else 0.78
            add(buf, b0 + int(e * 0.5 * BEAT), bell(BELL_TONES[bar][idx], velocity))
    return buf


def normalize(x: np.ndarray, rms_dbfs: float = -20.0, peak_dbfs: float = -1.0) -> np.ndarray:
    """Equal loudness across stems (RMS target), never above `peak_dbfs`."""
    rms = math.sqrt(float(np.mean(x * x))) or 1.0
    gain = 10 ** (rms_dbfs / 20.0) / rms
    peak = float(np.max(np.abs(x))) * gain
    ceiling = 10 ** (peak_dbfs / 20.0)
    if peak > ceiling:
        gain *= ceiling / peak
    return x * gain


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    ios_dir = sys.argv[2] if len(sys.argv) > 2 else None
    os.makedirs(out_dir, exist_ok=True)
    if ios_dir:
        os.makedirs(ios_dir, exist_ok=True)
    # (signal, RMS target). Noise is dense where the ear is most sensitive, so the rhythm
    # sits a few dB under the tonal parts to sound as loud as them; the bass a little over.
    stems = {
        "garden_beat": (render_beat(), -23.0),
        "garden_bass": (render_bass(), -19.0),
        "garden_pad": (render_pad(), -21.0),
        "garden_bells": (render_bells(), -21.0),
    }
    for name, (signal, rms_target) in stems.items():
        data = normalize(signal, rms_dbfs=rms_target).astype(np.float32)
        path = os.path.join(out_dir, f"{name}.ogg")
        # compression_level 0.6 = Vorbis quality ~0.4 (q4), transparent for these sounds.
        sf.write(path, data, SR, format="OGG", subtype="VORBIS", compression_level=0.6)
        rms = 20 * math.log10(math.sqrt(float(np.mean(data * data))) + 1e-12)
        peak = 20 * math.log10(float(np.max(np.abs(data))) + 1e-12)
        print(f"{path}: {len(data)} frames, rms {rms:.1f} dBFS, peak {peak:.1f} dBFS, "
              f"{os.path.getsize(path) // 1024} KB")
        if ios_dir:
            caf = os.path.join(ios_dir, f"{name}.caf")
            with tempfile.TemporaryDirectory() as tmp:
                pcm = os.path.join(tmp, f"{name}.caf")
                sf.write(pcm, data, SR, format="CAF", subtype="PCM_16")
                # 96 kb/s target; afconvert settles near 60-100 kb/s for these mono parts.
                subprocess.run(["afconvert", "-f", "caff", "-d", "aac", "-b", "96000", pcm, caf],
                               check=True)
            print(f"{caf}: {os.path.getsize(caf) // 1024} KB")


if __name__ == "__main__":
    main()
