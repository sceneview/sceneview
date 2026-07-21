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

| Pattern                       | Play `imageType`       | Slot                  | Reproducible by the script?        |
|-------------------------------|------------------------|-----------------------|------------------------------------|
| `phone-screenshot-{N}.png`    | `phoneScreenshots`     | Phone, 1080x2304      | **Yes** (default)                  |
| `tablet7-screenshot-{N}.png`  | `sevenInchScreenshots` | 7" tablet             | **Yes** — `--form-factor tablet7`  |
| `tablet10-screenshot-{N}.png` | `tenInchScreenshots`   | 10" tablet            | **Yes** — `--form-factor tablet10` |
| `icon-512.png`                | `icon`                 | Store icon, 512x512   | Sourced from `branding/`           |
| `feature-graphic.png`         | `featureGraphic`       | Feature graphic       | Sourced from `branding/`           |

The `imageType` column is the Play `AppImageType` enum value that
`store-sync/play_listing.py` uploads each pattern to. Those names are not
guessable — an invalid one 400s and, because a Play edit is atomic, voids the
**whole** listing sync including the text and the icon (#2794).

> ⚠️ **The committed tablet PNGs are known-bad — tracked in #2796.** They are
> byte-identical duplicates across the 7"/10" slots, light-mode (so they do not
> match iOS), advertise a stale `v4.14.0` in the About screen, and two of the six
> show no 3D content at all. `capture-play-store-screenshots.sh` has a **phone
> path only**, so they cannot currently be regenerated. Do not treat them as a
> reference for what the listing should look like.

## Regenerating the screenshots

```bash
# Phone (default) — on the Pixel-class QA AVD
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/capture-play-store-screenshots.sh

# Tablets — each class on a device of its OWN size, never the same one twice.
# `--settle 50`: a tablet framebuffer is ~4 Mpx and the GLB loads much slower
# there than on the phone rig. At the 15 s default the hero model is still
# loading when the shutter fires and the variance guard rejects a black frame.
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/capture-play-store-screenshots.sh --form-factor tablet10 --settle 50
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/capture-play-store-screenshots.sh --form-factor tablet7  --settle 50
```

Pin `ANDROID_SERIAL` explicitly — the script refuses to run against an ambiguous
adb default device, and routine QA must never target a personal phone. Override
the set or the output directory when needed:

```bash
bash .claude/scripts/capture-play-store-screenshots.sh \
  --demos model-viewer,lighting \
  --status-bar-px auto
```

### Tablet AVDs

`setup-ar-emulator.sh` builds the phone QA rig only. Create the tablet AVDs once:

```bash
avdmanager create avd -n Tablet10_QA -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d pixel_tablet
avdmanager create avd -n Tablet7_QA  -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d "Nexus 7 2013"
```

Then edit each `~/.android/avd/<name>.avd/config.ini`: set `hw.gpu.enabled = yes`
and `hw.gpu.mode = host` (the default is `no`, which renders Filament in software
and yields dark, unusable captures), and give them ≥ 2560 MB of `hw.ramSize` —
a tablet framebuffer is ~4 Mpx and the demo was observed dying mid-series at
2048 MB, which is what let an Android launcher screenshot into the set.

> **Why two AVDs and not one.** The 12 tablet PNGs this set replaced were
> byte-identical across the 7"/10" slots — the 10" capture had simply been
> re-uploaded into the 7" slot. Capturing both classes on one device reproduces
> exactly that defect, so each class comes from a device of its own size (#2796).

> **Why tablets are captured in portrait.** The demos frame their scene for a
> portrait viewport. In a tablet's natural landscape orientation the subject
> collapses to roughly 5% of the frame width — `double-pendulum` came out uniform
> enough that the variance guard rejected it outright. The script derives the
> rotation from `wm size` rather than hardcoding it, because a 10" tablet is
> landscape-native while a 7" one is portrait-native.

The script builds a debug APK, launches each demo cold via
`am start --es demo <id>`, captures with the Google `android` CLI (no
`adb shell screencap` LF/CRLF corruption), crops the status bar, and runs a
**variance check** on each capture so a blank/uniform frame fails loudly rather
than silently shipping to the store — the #917 failure mode. It also writes
`.mosaic-<form-factor>.png` for a quick visual pass before pushing.

Two guards exist because the variance check alone was **not** enough (#2796):

- **`pm clear` before every demo**, not `am force-stop`. Once the app has saved
  state it restores the last-viewed demo and silently ignores the `--es demo`
  extra — observed live, `--es demo model-viewer` re-opened *Picking &
  Collision*. Only wiping app data makes each launch deterministic.
- **A foreground assertion after each launch.** Variance only rejects a *uniform*
  frame, so it accepted an Android **launcher** screenshot (variance 679, Play
  Store icons and all) when the app had died mid-series. The script now refuses
  to capture unless the demo package actually owns the screen.

Always look at the mosaic. A capture can clear every automated guard and still
be wrong for a store — a correctly-rendered demo that occupies 5% of the frame
passes the variance check and is still unusable.

## Known follow-ups

- **#2796** — tablet screenshots: stale, duplicated, light-mode, non-3D.
- **#2785** — framing: the model occupies too little of the frame. Android has a
  `camera_distance` launch lever (#2652); iOS does not yet, so both are re-framed
  once that lands.
