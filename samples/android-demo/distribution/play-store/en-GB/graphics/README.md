# Play Store graphics

Store listing artwork for `io.github.sceneview.demo`. The `play-store.yml`
listing-sync job uploads this directory to the Play Console (#1710).

This is the Android counterpart of
[`samples/ios-demo/appstore-screenshots/README.md`](../../../../../ios-demo/appstore-screenshots/README.md).
Both stores are *meant* to show the same demos framed the same way (#2773) — but
they are **not in sync today**. Read the next section before assuming parity.

## What each class actually ships right now

| Class | Files | Set |
|---|---|---|
| `phone-screenshot-*` | 3 | **v2** — `model-viewer · dynamic-sky · multi-model` (#2854/#2855) |
| `tablet7-screenshot-*` | 5 | pre-v2 five — not re-shot in the v2 pass (#2907) |
| `tablet10-screenshot-*` | 5 | pre-v2 five, `materials` slot re-captured in #2903 |
| iOS (`appstore-screenshots/`) | 5 + 5 | pre-v2 five — refresh deferred on #2896 |

**Set v2** is what the capture script produces today. It is three frames
deliberately — fewer strong shots beat more mixed ones — each judged on the
captured mosaic rather than picked a-priori (#2854):

| # | Demo id        | Why it is in the set                                     |
|---|----------------|----------------------------------------------------------|
| 1 | `model-viewer` | Hero model, orbit camera — the load-any-GLB flagship      |
| 2 | `dynamic-sky`  | Lit drone against a procedural sky — the strongest frame  |
| 3 | `multi-model`  | The only non-helmet, non-sky frame — photoreal foliage    |

**Retired, do not re-add by guesswork** (the same list sits in the capture
script next to `DEMOS_DEFAULT`): `materials` picks a different HDRI *and* model
each launch, so the frame is not reproducible (#2874); `geometry` clips its
primitives in a portrait frame (#2873); `double-pendulum` is a tiny linkage in a
~95%-black frame and ignores reframing; `fog` stayed a low-contrast grey helmet;
`animation` duplicates slot 1. The pre-v2 tablet sets above still contain three
of these — that is the gap #2907 tracks, not an endorsement.

**`multi-model` is phone-only.** At a tablet's wider aspect (~0.64 w/h vs the
phone's ~0.47) its FIXED camera angle lands on a wooden support post against the
backdrop wall — no foliage. Not a load/settle defect (the frame renders fully),
and the framing lever cannot fix it: probed at 2.5 / 3.5 / 4.5 m on both tablet
AVDs the frame is essentially identical, because `camera_distance` moves the
camera *along* an angle it cannot change. The variance guard **passes** it (2227
on 10", 2827 on 7"), so only the mosaic eyeball catches it — the script drops it
from tablet runs so a green capture cannot silently ship it. Fix is demo-side,
tracked in #2913.

On Android the v2 ids resolve through `ALIAS_INITIAL_TAB` to distinct umbrella
tabs (`dynamic-sky` → Lighting Lab, `multi-model` → the Multi-Model tab), so no
two slots collapse onto the same screen. The script forces a **dark appearance**
and neutralises the status bar.

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

All three classes are script-reproducible, but only phone is on the current set
(see the table above). For the record, what the tablet PNGs replaced (#2796): 12
files that were byte-identical across the 7"/10" slots, light-mode, advertised a
stale `v4.14.0` in the About screen, and included two screens with no 3D at all.
The committed tablet PNGs were shot from a **4.23.0** build, so they also predate
the demo bottom-overlay fix (#2780) — another reason #2907 wants a re-capture.

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
  --demos model-viewer,dynamic-sky \
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

Host budget, measured on the `pixel_tablet` profile: the emulator **rewrites
`config.ini` on every boot**, so a hand-lowered `disk.dataPartition.size` does
not stick — and `-partition-size` does not override it either. It provisions the
profile's 6 GiB userdata and refuses to start below **~7.4 GB free disk**
(`FATAL | Not enough space to create userdata partition`), and forces RAM to
**4096 MB** regardless of `-memory`. Check free disk *and* RAM before booting;
on a busy host, wait for another QA run to finish rather than racing it. To
reclaim the space afterwards, delete the AVD's `userdata-qemu.img*` — it is
regenerated on the next boot (this frees ~5 GB per tablet AVD).

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

- **#2907** — the tablet classes still carry the pre-v2 five, shot from a 4.23.0
  build. A re-capture on set v2 needs both tablet AVDs and a fresh app build.
- **#2913** — `multi-model` cannot ship on tablets until its scene takes the
  viewport aspect into account (demo-side). Until then a tablet run yields two
  slots; Play accepts 2–8 per type.
- **#2874 / #2873** — `materials` (non-deterministic HDRI *and* model per
  launch) and `geometry` (clipped primitives) are what keep those ids out of
  set v2. Both are demo-side and still open.
- **#2785** — the `camera_distance` lever (#2652, `--ef camera_distance <f>`)
  is honoured only by demos built on `rememberHeroOrbitCameraManipulator`, and
  iOS has no equivalent launch argument at all. That gap is part of what blocks
  the iOS re-capture (#2896).
