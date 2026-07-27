# Play Store graphics

Store listing artwork for `io.github.sceneview.demo`. The `play-store.yml`
listing-sync job uploads this directory to the Play Console (#1710).

This is the Android counterpart of
[`samples/ios-demo/appstore-screenshots/README.md`](../../../../../ios-demo/appstore-screenshots/README.md).
Both stores are *meant* to show the same demos framed the same way (#2773), but
they are **not in sync today**: Android is on set v2 while the iOS images are
still the pre-v2 five, deferred on #2896. Check both READMEs before assuming
parity.

## The showcase set — v2 (#2854)

| # | Demo id        | Classes      | Why it is in the set                                  |
|---|----------------|--------------|-------------------------------------------------------|
| 1 | `model-viewer` | phone + tablets | Hero model, orbit camera — the load-any-GLB flagship |
| 2 | `dynamic-sky`  | phone + tablets | Lit drone against a procedural sky — the strongest frame |
| 3 | `multi-model`  | **phone only**  | The only non-helmet, non-sky frame — photoreal foliage |

**Why tablets ship two (#2907/#2913).** A tablet portrait frame is ~0.63 w/h
against the phone's ~0.47, and at that aspect `multi-model`'s FIXED camera angle
lands on a wooden support post against the backdrop wall — no foliage. It is not
a load/settle problem (the frame renders fully) and the framing lever cannot fix
it: probed at 2.5 / 3.5 / 4.5 m on both tablet AVDs, the frame is essentially
identical, because `camera_distance` moves along an angle it cannot change. The
variance guard passes it (2227 on 10", 2827 on 7"), so **only the mosaic eyeball
catches it** — the script now drops it from tablet runs so a green capture can
never silently re-ship it.

Fewer strong frames beat more mixed ones. **Retired, do not re-add by
guesswork** (the same list is in the capture script next to `DEMOS_DEFAULT`):
`materials` picks a different HDRI *and* model each launch, so the slot is not
reproducible (#2874); `geometry` clips its primitives in a portrait frame
(#2873); `double-pendulum` is a tiny linkage in a ~95%-black frame and ignores
reframing; `fog` stayed a low-contrast grey helmet; `animation` duplicates
slot 1.

On Android the v2 ids resolve through `ALIAS_INITIAL_TAB` to distinct umbrella
tabs (`dynamic-sky` → Lighting Lab, `multi-model` → the Multi-Model tab), so no
two slots collapse onto the same screen.

The **iOS** listing still holds the pre-v2 five — its refresh is blocked on
scene-side RealityKit fixes (#2896), so the two stores are deliberately not in
sync yet. The script forces a **dark appearance** and neutralises the status bar.

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

All three classes are script-reproducible and captured from set v2. For the
record: the tablet PNGs first replaced 12 files that were byte-identical across
the 7"/10" slots, light-mode, advertised a stale `v4.14.0`, and included two
screens with no 3D at all (#2796); the tablet classes then lagged one set behind
when #2855 moved phone to v2 without re-shooting them (#2907).

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
not stick and `-partition-size` does not override it either — it provisions the
profile's 6 GiB userdata and refuses to start below **~7.4 GB free disk**
(`FATAL | Not enough space to create userdata partition`). It also forces RAM up
to **4096 MB** regardless of `-memory`. Check free disk *and* RAM before booting;
on a busy host, wait for another QA run to finish rather than racing it.

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

- **#2785** — framing lever, iOS side. The `camera_distance` argument
  (#2652, `--ef camera_distance <f>` on Android) is wired per demo in the
  capture script — set v2 uses 4.5 for `model-viewer` and 6.0 for
  `multi-model` — but it is **not** wired into every demo, and iOS has no
  equivalent launch arg at all. That gap is what blocks the iOS re-capture
  (#2896).

  The framing defects previously listed here (`geometry` cropping its cube,
  `double-pendulum` off-centre) no longer affect the shipped set: both demos
  were retired from it in #2854. They are tracked on their own issues
  (#2873 for `geometry`) and would need fixing before either id could return.
