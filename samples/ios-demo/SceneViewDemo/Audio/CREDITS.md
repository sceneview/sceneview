# Audio assets — credits & licenses

All audio bundled here is CC0 / public domain so it can ship inside the open-source
SceneView demos and the Play Store / App Store / Web demo APKs without any
attribution friction.

| File | Source | License | Description |
|---|---|---|---|
| `bell.wav` | Generated locally with `ffmpeg` (sine 880 Hz, 0.6 s, fade-in 10 ms / fade-out 550 ms, 22 050 Hz mono PCM 16-bit) | [CC0 / public domain](https://creativecommons.org/publicdomain/zero/1.0/) | Soft bell tone, suitable for the bouncing-sphere `SpatialAudioDemo` on every platform |
| `garden_beat`, `garden_bass`, `garden_pad`, `garden_bells` (`.ogg` on Android, `.caf` on iOS) | Synthesized from code by `tools/generate-sound-garden-stems.py` (sine and noise synthesis, fixed seed — no samples, no recordings; 48 kHz mono, 512 000 frames each) | [CC0 / public domain](https://creativecommons.org/publicdomain/zero/1.0/) | The four parts of one 4-bar loop (A minor, 90 BPM) played by the AR Sound Garden demo, one part per orb |

## Regenerating the Sound Garden stems

```bash
python3 -m venv /tmp/sg && /tmp/sg/bin/pip install numpy soundfile
/tmp/sg/bin/python tools/generate-sound-garden-stems.py \
    samples/android-demo/src/main/assets/audio \
    samples/ios-demo/SceneViewDemo/Audio
```

The `.caf` files are AAC encoded by Apple's `afconvert` (macOS only); their packet table
records the encoder priming, so every part decodes to exactly one loop.

## Regenerating `bell.wav`

```bash
ffmpeg -y -f lavfi -i "sine=frequency=880:duration=0.6" \
       -af "afade=t=in:st=0:d=0.01,afade=t=out:st=0.05:d=0.55" \
       -ar 22050 -ac 1 -sample_fmt s16 \
       assets/audio/bell.wav
```

The output is deterministic across `ffmpeg` versions because the parameters
(sine generator, fade envelope, sample rate, mono channel, 16-bit PCM) are all
fully specified. A regenerated file should be byte-identical to the committed
one (≤ 1-2 bytes of header drift across major versions).
