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
| `tablet7-screenshot-*` | 2 | **v2** — `model-viewer · dynamic-sky` (#2907; `multi-model` is back in the script since #2913 but these files predate it) |
| `tablet10-screenshot-*` | 2 | **v2** — `model-viewer · dynamic-sky` (#2907; `multi-model` is back in the script since #2913 but these files predate it) |
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

**`multi-model` was phone-only for one release, and is back on tablets (#2913).**
What had been measured was real: at a tablet's wider aspect (~0.64 w/h vs the
phone's ~0.47) the capture landed on a wooden post against the backdrop wall, no
foliage, and the variance guard **passed** it (2227 on 10", 2827 on 7"). Both
stated causes turned out to be wrong, which is worth keeping on the record:

- The framing was broken at **every** aspect, not just on tablets. The section
  aimed a fixed camera at the formation centre it authored while the library's
  `autoCenterContent` pass had already translated that formation onto the world
  origin — the lens sat ~0.6 m from the centroid, *inside* the subject. A narrow
  phone frame cropped that into something that reads as texture; a wider tablet
  frame exposed it. The scene now derives its camera distance from the live
  viewport aspect and frames itself on both classes.
- `camera_distance` was inert here because the section read no `DemoSettings` at
  all, not because "distance moves the camera along an angle it cannot change".
  The 2.5 / 3.5 / 4.5 m probe compared three frames that each discarded the extra.

The guard lesson stands unchanged, and is the reason this paragraph exists: a
green capture of this demo is never evidence. Judge the mosaic by eye.

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

All three classes are script-reproducible and all three are now on set v2 — phone
with three slots, the committed tablet files with two, because they were captured
while `multi-model` was dropped from tablet runs; a fresh tablet run now writes
three (Play accepts 2–8 per type). The set is what a run *writes*, and a run also **prunes**
any higher-numbered slot left over from a larger set, because `play_listing.py`
selects by glob rather than by count — an unpruned leftover would still be
uploaded at the next tag, and the mosaic (which iterates 1..N) cannot show it.

For the record, what the tablet PNGs replaced (#2796): 12 files that were
byte-identical across the 7"/10" slots, light-mode, advertised a stale `v4.14.0`
in the About screen, and included two screens with no 3D at all. The pre-v2
tablet PNGs that #2907 has now retired were shot from a **4.23.0** build and
predate the demo bottom-overlay fix (#2780).

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

### `multi-model` shoots a different scene without a Sketchfab key (#2913)

That demo renders whatever the Sketchfab resolver hands back. **With** a key
(`SKETCHFAB_API_KEY`, or `sketchfab.api.key` in `local.properties`) it streams the
`park` category — the photoreal scanned oaks the slot exists for. **Without** one it
substitutes each slug's bundled fallback: a lantern, a lantern, a shiba, a soldier.
Same demo id, same layout, an entirely different picture — and nothing in the
capture path can tell the two apart, because the frame renders fully, the foreground
guard passes, and centre-variance is high either way. The script now WARNs when the
key is missing and `multi-model` is in the set; it does not fail, because a keyless
capture of the other slots is perfectly valid.

This is worth knowing before reading a capture of that demo as evidence. The
"wooden support post" in #2913's tablet frames is the bundled lantern's post —
identical to what a keyless capture produces here — while the committed
`phone-screenshot-3.png` it was compared against is a streamed oak. Two different
scenes, on top of the (real, separate) framing defect that fix addresses.

### Per-viewport framing (#2913)

`multi-model` computes its camera distance from its own formation size and the
**live viewport aspect**, so it frames itself on a phone and on a tablet without a
per-class value here — the `camera_distance` extra is deliberately not set for it in
`camera_distance_for()`. Passing one would override the per-viewport framing with a
single number tuned on one screen shape. Other demos still take the extra; see the
framing notes next to `camera_distance_for()` for which ones actually read it.

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

- **#2913 (fixed, awaiting a re-capture)** — the scene now takes the viewport
  aspect into account, and `multi-model` is back in the tablet set. The committed
  tablet PNGs still hold two slots because they predate the fix; a tablet run
  writes three. Re-capturing them is the remaining step (Play accepts 2–8 per
  type, so the two-slot listing is valid meanwhile).
- **Tablet `model-viewer` framing is not deterministic.** In the committed set
  the helmet sits at a visibly different orientation on `tablet7-screenshot-1`
  than on `tablet10-screenshot-1`, while `dynamic-sky` matches across both
  classes. Two runs of the same demo should differ only by aspect, so this is a
  demo-side non-determinism worth pinning before the next re-capture.
- **#2874 / #2873** — `materials` (non-deterministic HDRI *and* model per
  launch) and `geometry` (clipped primitives) are what keep those ids out of
  set v2. Both are demo-side and still open.
- **#2785** — the `camera_distance` lever (#2652, `--ef camera_distance <f>`)
  is honoured only by demos built on `rememberHeroOrbitCameraManipulator`, and
  iOS has no equivalent launch argument at all. That gap is part of what blocks
  the iOS re-capture (#2896).
