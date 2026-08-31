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
| `phone-screenshot-*` | 4 | **v3 + AR slot 1 (#2844)** — slot 1 is the generated AR visual (see "The #2844 AR set" below); slots 2–4 stay the v3 captures of the redesigned demo (#3321): `Model Viewer · Lighting Lab · Materials`, captured manually (the capture script is gone, #3244) |
| `tablet7-screenshot-*` | 4 | **v3 + AR slot 1 (#2844)** — slot 1 generated; slots 2–4 are the `Model Viewer · Lighting Lab · Materials` re-captures from `Tablet7_QA` (#3350) |
| `tablet10-screenshot-*` | 4 | **v3 + AR slot 1 (#2844)** — slot 1 generated; slots 2–4 are the `Model Viewer · Lighting Lab · Materials` re-captures from `Tablet10_QA` (#3350) |
| iOS (`appstore-screenshots/`) | 3 + 3 | **v2 + AR slot 1 (#2844)** — `00-ar` (generated AR visual, filename order puts it first) · `model-viewer` · `dynamic-sky`. The two captured frames are the set #2896 deliberately curated (`multi-model` excluded: a keyless capture build substitutes bundled stand-ins, so the frame is not the scene the demo documents) |

**Set v2** is what the now-removed capture script produced (see below). It is three frames
deliberately — fewer strong shots beat more mixed ones — each judged on the
captured mosaic rather than picked a-priori (#2854):

| # | Demo id        | Why it is in the set                                     |
|---|----------------|----------------------------------------------------------|
| 1 | `model-viewer` | Hero model, orbit camera — the load-any-GLB flagship      |
| 2 | `dynamic-sky`  | Lit drone against a procedural sky — the strongest frame  |
| 3 | `multi-model`  | The only non-helmet, non-sky frame — photoreal foliage    |

**Retired, do not re-add by guesswork** (the same list sits in the capture
script next to `DEMOS_DEFAULT`): `materials` was not reproducible launch to
launch, and its demo side is fixed as of #2874 — eligible again, but only after
someone captures it and looks at the frame; `geometry` no longer clips
(#2873) but its cluster leaves the frame centre empty, which the variance guard
reads as blank; `double-pendulum` is a tiny linkage in a
~95%-black frame and ignores reframing; `fog` stayed a low-contrast grey helmet;
`animation` duplicates slot 1. No committed class carries any of them any more —
the last holdouts were the pre-v2 tablet sets #2907 retired.

**`multi-model` was phone-only for one release, and is back on tablets (#2913).**
What had been measured was real: at a tablet's wider aspect (0.625 w/h on both
QA AVDs, against the phone's ~0.47) the capture landed on a wooden post against the backdrop wall, no
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
| `phone-screenshot-{N}.png`    | `phoneScreenshots`     | Phone, 1080x2304      | **No** — manual since #3244        |
| `tablet7-screenshot-{N}.png`  | `sevenInchScreenshots` | 7" tablet             | **No** — manual since #3244        |
| `tablet10-screenshot-{N}.png` | `tenInchScreenshots`   | 10" tablet            | **No** — manual since #3244        |
| `icon-512.png`                | `icon`                 | Store icon, 512x512   | Sourced from `branding/`           |
| `feature-graphic.png`         | `featureGraphic`       | Feature graphic, 1024x500 | **No** — generated AR visual (#2844), no longer sourced from `branding/` |

The `imageType` column is the Play `AppImageType` enum value that
`store-sync/play_listing.py` uploads each pattern to. Those names are not
guessable — an invalid one 400s and, because a Play edit is atomic, voids the
**whole** listing sync including the text and the icon (#2794).

## The #2844 AR set

Slot 1 of every screenshot class and the feature graphic are **generated AR
visuals, not captures** — the demo has no AR screen that photographs this well,
and ARCore replay captures were never produced for this set. Provenance, so the
set can be regenerated or audited:

- Generated with Gemini `gemini-3.1-flash-image` from
  `tools/demo-previews/refs/hero.webp` (the battle-worn sci-fi helmet — the same
  reference model as the in-app previews), composited photoreal-AR into a real
  living room: contact shadow, reflections, depth of field. The prompts forbid
  text, logos, UI, device frames and people.
- Raw Gemini output is centre-cropped to each aspect then Lanczos-resized to the
  exact slot size (1024x500 feature · 1080x2304 phone · 1200x1872 7" ·
  1600x2512 10").
- Each item exists in two lighting variants. The **dark** one (evening interior,
  warm floor lamp) is committed here; the **light** one (window daylight) lives
  in [`../../alternates-light/`](../../alternates-light/README.md) — outside
  this directory on purpose, because `play_listing.py`'s test suite fails on
  any file here that no `imageType` pattern claims (same rule as the mosaic,
  see below).

The iOS `00-ar` frames in `samples/ios-demo/appstore-screenshots/` come from the
same generation run.

No class is script-reproducible any more (#3244 removed the script). The phone
class ships set v3 at four slots since #3321; both tablet classes ship the same
v3 set at four slots each since #3350 (Play accepts 2–8 per type). The tablet files sat at two for
one release — they were captured while `multi-model` was dropped from tablet runs
and were not re-shot when #2913 fixed its framing; #3106 re-captured them on v2,
then #3350 re-shot them on v3. The
set is what a run *writes*, and a run also **prunes**
any higher-numbered slot left over from a larger set, because `play_listing.py`
selects by glob rather than by count — an unpruned leftover would still be
uploaded at the next tag, and the mosaic (which iterates 1..N) cannot show it.

For the record, what the tablet PNGs replaced (#2796): 12 files that were
byte-identical across the 7"/10" slots, light-mode, advertised a stale `v4.14.0`
in the About screen, and included two screens with no 3D at all. The pre-v2
tablet PNGs that #2907 has now retired were shot from a **4.23.0** build and
predate the demo bottom-overlay fix (#2780).

## Regenerating the screenshots

> **The capture script (`capture-play-store-screenshots.sh`) was removed in
> #3244** along with the rest of the agent harness, and nothing replaced it. A
> re-capture today is manual: build the debug APK, drive each screen on a QA
> AVD (never a personal device — `emulator-5554` for phone, the tablet AVDs
> below for tablets), `adb exec-out screencap`, crop the status bar (96 px on
> the phone AVD), and judge every frame by eye — set v3 (#3321) was produced
> exactly this way. Everything below is kept as the record of how the tablet
> sets were shot and of every capture-side trap that scripted era uncovered;
> re-read it before any re-capture, the traps have not moved.

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

### Per-form-factor framing (#3106)

`model-viewer` does take the extra, and its value is **per form factor**: 4.5 m on
phone, 4.0 m on both tablet classes. The 4.5 m was judged on the phone's ~0.47 w/h
frame; at the tablets' 0.625 it left the helmet at about a third of the frame
height on mostly black. Probed live and judged on the captures rather than on
variance — 3.0 m crops the chin piece, 3.5 m fits a 3/4 pose but clips the head-on
one (the hero orbit is free-running, so the pose is a lottery and a distance must
survive the whole orbit, not the frame that happened to be captured), 4.0 m fills
the frame at every instant. A single tablet value covers both classes because
`Tablet7_QA` (1200x1920 natively) and `Tablet10_QA` (2560x1600 natively, rotated
to 1600x2560 by the capture script) land on the same
0.625 aspect; re-probe if an AVD with a different ratio is ever added.

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

- **#2913 (fixed and re-captured, #3106)** — the scene takes the viewport aspect
  into account, `multi-model` is back in the tablet set, and both tablet classes
  were re-shot at three slots. No follow-up left here.
- **Tablet Model Viewer framing is not deterministic — but the committed v3 pair
  happens to match.** The cause is the free-running hero orbit: the captured pose
  is whatever instant the settle window lands on, so two runs of the same demo
  can differ by more than aspect — which is exactly what the v2 set showed (a
  visibly different helmet orientation on `tablet7-screenshot-1` vs
  `tablet10-screenshot-1`, still true after #3106). The v2 evidence is gone:
  #3350 re-captured both tablet classes with set v3, where Model Viewer sits at
  slot 2, and judged by eye the helmet holds the same head-on pose on
  `tablet7-screenshot-2` and `tablet10-screenshot-2` — the committed set no
  longer shows the mismatch. The orbit itself is still unpinned, so any future
  re-capture rolls the pose lottery again; that is why the tablet distance was
  probed at several orbit instants and chosen to survive the *widest* pose rather
  than to suit one frame: 3.5 m looked right on the 3/4 pose and clipped the
  head-on one.
- **#2874** — `materials` was not reproducible: a streamed subject and an
  orbiting camera against the `studio_2k` skybox meant the frame depended on the
  API key, the network and capture timing. The demo side is **fixed** (bundled
  default subject, one fixed studio HDRI, subject-independent framing), so the id
  is eligible for a slot again — it stays out of the committed set only because
  nobody has captured it and judged the frame against the other slots yet.
- **#2873 — `geometry`'s clipping is fixed; the id still stays out of set v2.**
  The demo laid its four primitives on a row ~1.45 m wide and viewed it from a
  camera that measured **1.22 m** away — not the 2.7 m its own comment claimed,
  because the orbit distance is the *length* of `orbitHomePosition`: Filament
  takes that value as the eye verbatim, and `autoCenterContent = true` has
  already moved the content onto the world origin, so `targetPosition` never
  enters the distance (documented on `main` in #2930). Both halves
  are fixed: the primitives sit in a 2 × 2 cluster, the distance means what it
  says, and `camera_distance` is wired into this demo. Measured on the QA
  emulator at the default framing, the cluster clears the frame with ≥ 184 px of
  margin per side (predicted left edge 187.2 px, measured 187 px), and
  `GeometryLayoutTest` pins the fit arithmetic.

  Two capture-side reasons still argue against the slot, and neither is fixed by
  the framing work. The 2 × 2 cluster leaves the frame **centre** empty, so the
  3 × 3 centre-patch variance guard reads it as blank (measured 0.1, threshold
  100) — a capture run on this id fails until either the layout or the guard
  changes. And the shared Y-axis spin is free-running outside `qa_mode`, so the
  flat plane is edge-on — invisible — at an unpredictable fraction of capture
  instants. Judge a fresh mosaic before adding the id back.
- **#2785** — the `camera_distance` lever (#2652, `--ef camera_distance <f>`)
  is honoured only by demos built on `rememberHeroOrbitCameraManipulator`, and
  iOS has no equivalent launch argument at all. That gap is part of what blocks
  the iOS re-capture (#2896). `geometry` is wired in as of #2873; every other
  non-hero-orbit demo still swallows the extra silently.
