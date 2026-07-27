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

All three classes are now script-reproducible and captured from the unified demo
set. For the record, what the tablet PNGs replaced (#2796): 12 files that were
byte-identical across the 7"/10" slots, light-mode, advertised a stale `v4.14.0`
in the About screen, and included two screens with no 3D at all.

## Regenerating the screenshots

```bash
# Phone (default) — on the Pixel-class QA AVD
ANDROID_SERIAL=emulator-5554 bash .claude/scripts/capture-play-store-screenshots.sh

# Tablets — each class on a device of its OWN size, never the same one twice.
# These default to a 50 s settle (vs 15 s on phone): a tablet framebuffer is
# ~4 Mpx and the GLBs load far slower there, so at 15 s the hero model is still
# loading when the shutter fires and the variance guard rejects a black frame.
ANDROID_SERIAL=emulator-5556 bash .claude/scripts/capture-play-store-screenshots.sh --form-factor tablet10   # a 10" pixel_tablet AVD — NOT the phone's 5554
ANDROID_SERIAL=emulator-5558 bash .claude/scripts/capture-play-store-screenshots.sh --form-factor tablet7    # a 7" Nexus 7 AVD — a third, distinct device
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
`$TMPDIR/sceneview-store-capture/mosaic-<form-factor>.png` for a quick visual
pass before pushing. That file lands **outside** this directory on purpose: this
directory mirrors the Play listing byte-for-byte, and `play_listing.py`'s test
suite fails on any file here that no `imageType` pattern claims — it caught the
mosaic when it was first written alongside the PNGs.

Three guards exist because the variance check alone was **not** enough (#2796):

- **A one-shot `pm clear` + cache warm-up** before the run. Once the app has
  saved state it restores the last-viewed demo and silently ignores the
  `--es demo` extra — observed live, `--es demo model-viewer` re-opened *Picking
  & Collision*. It is deliberately NOT per-demo: clearing app data every
  iteration also drops the asset cache, and the model then fails to load inside
  the settle window (captured as a black viewport with only the "Surprise me"
  button).
- **A foreground assertion after each launch.** Variance only rejects a *uniform*
  frame, so it accepted an Android **launcher** screenshot (variance 679, Play
  Store icons and all) when the app had died mid-series. The script now refuses
  to capture unless the demo package actually owns the screen.
- **An install verification.** `android run` can no-op the install and still
  exit 0, so the `|| adb install` fallback never fires and the run dies on the
  first `am start` with no output at all (`set -e`). The script checks that
  `pm path <pkg>` resolves, retries with `adb install -r`, and fails loudly
  otherwise. (`pm clear` is no use as a probe — it prints "Failed" while still
  exiting 0.)

Always look at the mosaic. A capture can clear every automated guard and still
be wrong for a store — a correctly-rendered demo that occupies 5% of the frame
passes the variance check and is still unusable.

## Known follow-ups

- **#2785** — framing. `model-viewer` and `double-pendulum` sit noticeably
  off-centre. The `camera_distance` lever (#2652, `--ef camera_distance <f>`)
  is honoured only by demos built on `rememberHeroOrbitCameraManipulator`; on
  any other demo it is a **silent no-op**, which is what makes a framing defect
  look unfixable from the capture side. Wiring it per demo is #2785's scope.

- **#2873 — `geometry` framing: FIXED, but NOT re-added to the set.** The demo
  laid its four primitives out on a row ~1.45 m wide and viewed it from a
  camera that measured **1.22 m** away — not the 2.7 m its own comment claimed,
  because `orbitHomePosition` resolves as an offset whose *length* is the orbit
  distance, not as a world position differenced against the target. Both halves
  are fixed: the primitives now sit in a 2 × 2 cluster, the distance means what
  it says, and `camera_distance` is wired into this demo. Measured on the QA
  emulator at the default framing, the cluster clears the frame with ≥ 184 px of
  margin on each side (predicted left edge 187.2 px, measured 187 px), and
  `GeometryLayoutTest` pins the fit arithmetic.

  Re-adding `geometry` to a capture default is deliberately left out of that
  change: it needs its own verified visual pass, and two things still argue
  against the slot. The four-primitive cluster leaves the frame **centre**
  empty, so the 3 × 3 centre-patch variance guard reads it as blank (measured
  0.1, threshold 100) — a capture run on this id fails until either the layout
  or the guard changes. And the shared Y-axis spin is free-running outside
  `qa_mode`, so the flat plane is edge-on — invisible — at an unpredictable
  fraction of capture instants. Judge a fresh mosaic before adding the id back.
