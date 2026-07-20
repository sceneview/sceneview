# Play Store graphics

Store listing artwork for `io.github.sceneview.demo`. The `play-store.yml`
listing-sync job uploads this directory to the Play Console (#1710).

This is the Android counterpart of
[`samples/ios-demo/appstore-screenshots/README.md`](../../../../../ios-demo/appstore-screenshots/README.md).
Both stores are meant to show the **same demos, framed the same way** — see
#2773.

## The unified showcase set (#2773)

Android and iOS capture the identical five demos, in this order:

| # | Demo id           | Why it is in the set                      |
|---|-------------------|-------------------------------------------|
| 1 | `model-viewer`    | Hero model, orbit camera — the flagship    |
| 2 | `lighting`        | PBR lighting, visually rich                |
| 3 | `materials`       | Material showcase                          |
| 4 | `geometry`        | Procedural primitives                      |
| 5 | `double-pendulum` | Physics/animation, shows motion            |

Both scripts force a **dark appearance** and neutralise the status bar so the
two listings look like one product rather than two unrelated apps.

## Files

| Pattern                    | Slot                  | Reproducible by the script? |
|----------------------------|-----------------------|-----------------------------|
| `phone-screenshot-{N}.png` | Phone, 1080x2304      | **Yes**                     |
| `tablet7-screenshot-{N}.png`  | 7" tablet          | **No** — see #2796          |
| `tablet10-screenshot-{N}.png` | 10" tablet         | **No** — see #2796          |
| `icon-512.png`             | Store icon, 512x512   | Sourced from `branding/`    |
| `feature-graphic.png`      | Feature graphic       | Sourced from `branding/`    |

> ⚠️ **The committed tablet PNGs are known-bad — tracked in #2796.** They are
> byte-identical duplicates across the 7"/10" slots, light-mode (so they do not
> match iOS), advertise a stale `v4.14.0` in the About screen, and two of the six
> show no 3D content at all. `capture-play-store-screenshots.sh` has a **phone
> path only**, so they cannot currently be regenerated. Do not treat them as a
> reference for what the listing should look like.

## Regenerating the phone screenshots

```bash
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/capture-play-store-screenshots.sh
```

Pin `ANDROID_SERIAL` explicitly — the script refuses to run against an ambiguous
adb default device, and routine QA must never target a personal phone. Override
the set or the output directory when needed:

```bash
bash .claude/scripts/capture-play-store-screenshots.sh \
  --demos model-viewer,lighting \
  --status-bar-px auto
```

The script builds a debug APK, launches each demo cold via
`am start --es demo <id>`, captures with the Google `android` CLI (no
`adb shell screencap` LF/CRLF corruption), crops the status bar, and runs a
**variance check** on each capture so a blank/uniform frame fails loudly rather
than silently shipping to the store — the #917 failure mode. It also writes
`.mosaic.png` for a quick visual pass before pushing.

## Known follow-ups

- **#2796** — tablet screenshots: stale, duplicated, light-mode, non-3D.
- **#2785** — framing: the model occupies too little of the frame. Android has a
  `camera_distance` launch lever (#2652); iOS does not yet, so both are re-framed
  once that lands.
