# Audio assets — credits & licenses

All audio bundled here is CC0 / public domain so it can ship inside the open-source
SceneView demos and the Play Store / App Store / Web demo APKs without any
attribution friction.

| File | Source | License | Description |
|---|---|---|---|
| `bell.wav` | Generated locally with `ffmpeg` (sine 880 Hz, 0.6 s, fade-in 10 ms / fade-out 550 ms, 22 050 Hz mono PCM 16-bit) | [CC0 / public domain](https://creativecommons.org/publicdomain/zero/1.0/) | Soft bell tone, suitable for the bouncing-sphere `SpatialAudioDemo` on every platform |

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
