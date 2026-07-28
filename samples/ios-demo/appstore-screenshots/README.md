# iOS App Store screenshots

Fresh, correctly-sized App Store Connect screenshots for the SceneView demo
app — real iOS-simulator captures of rendered 3D content.

## Background — issue #917

The App Store Connect listing previously carried stale screenshots:

- **iPhone 6.5"** — 4 images, all *Android-device* captures (Android status
  bar + UI chrome). Two of them were blank white AR scenes showing only a
  "Move your phone…" toast.
- **iPad 13"** — the same phone-aspect images letterboxed onto an iPad
  canvas, not genuine iPad captures.
- **iPhone 6.9"** (newest required size) — not populated.

These images are genuine iOS-simulator captures of demos that render rich,
full 3D content. No blank/loading AR scenes.

## Required device classes & dimensions

App Store Connect rejects off-spec images, so dimensions must be exact:

| Folder        | Device class   | Pixels      | Captured on                  |
|---------------|----------------|-------------|------------------------------|
| `iphone-6.9/` | iPhone 6.9"    | 1320 × 2868 | iPhone 16/17 Pro Max sim     |
| `ipad-13/`    | iPad 13"       | 2064 × 2752 | iPad Pro 13-inch (M4/M5) sim |

The iPhone 16 Pro Max and iPhone 17 Pro Max share the **same 6.9" display
class** (identical 1320×2868 screenshot spec); likewise the iPad Pro 13-inch
M4 and M5. Either generation produces an App Store Connect-compliant image
for its class.

## Demos captured (#2854, #2896)

Android's set v2 order, minus `multi-model` (see below). Both ids are
standalone demos that render rich 3D content with **no network** — deliberately
not empty or loading AR scenes:

1. `01-model-viewer` — bundled hero model (cyberpunk hovercar) on the `.warm`
   photo-studio backdrop, frozen on a three-quarter hero pose
2. `02-dynamic-sky` — procedural time-of-day skyline under a live HDRI sky

### Why `multi-model` is not here

⛔ **Do not re-add it by symmetry with the Android phone set.** An App Store
capture build has no Sketchfab key, so `SketchfabAssetResolver` substitutes the
registered bundled stand-ins (`SampleAssets.swift` — bench → `retro_piano.usdz`,
dog → `animated_butterfly.usdz`, bird → `phoenix_bird.usdz`). The captured frame
is therefore *not* the park diorama the demo documents: it is a retro piano with
a butterfly clipping through it and a phoenix beside it, with the tree slot not
rendering at all.

That frame passes every mechanical check — right dimensions, settled,
byte-reproducible — so only looking at the mosaic catches it. It is the same
defect class that took `multi-model` out of the Android **tablet** set
(#2913/#2915: "do not re-add on the strength of a green capture"), and shipping
it would advertise a scene no keyless user can ever see. Re-add once #2913 and
the tree-render bug land, then re-judge the mosaic.

Captured in **dark appearance** with a cleaned status bar (fixed 9:41, full
signal/battery), mirroring the Android capture's dark-mode + status-bar crop
so the two stores match visually.

The set is captured under `-qa_mode 1`, which freezes each demo's orbit
auto-rotation on its authored pose. That is what makes a re-capture comparable
to the committed one: two independent runs of this script produce byte-identical
frames (measured, 0 differing pixels). Without it the shot landed on whatever
azimuth the sweep had reached, so both the subject's pose and the slice of HDRI
behind it changed every run.

> **Two things that had to be fixed in the app before this set could ship
> (#2896)**, worth knowing if a future capture looks wrong again:
>
> - Every bundled HDR environment is a Radiance `.hdr`, which
>   `EnvironmentResource(named:)` cannot load. The failure was swallowed, so
>   scenes ran with **no IBL and no skybox** — dim subjects on black, and
>   `dynamic-sky` with no sky at all. `SceneEnvironment.load()` now falls back to
>   ImageIO. A `[SceneViewSwift] Failed to load environment '…'` line in the
>   console means this regressed.
> - iOS has no `camera_distance` launch argument (Android's framing lever,
>   tracked for iOS in #2785). The three scenes instead carry their own framing
>   via `.framingMargin(_:)` / `.cameraOrbit(azimuth:elevation:)`.

## How to regenerate

```bash
bash .claude/scripts/capture-appstore-screenshots.sh
```

The script builds the demo, then for each device class: erases the
simulator, installs the app, and for each demo launches it with the
`-demo <id>` launch argument — which routes straight to the demo on first
frame (see `SceneViewDemoApp.swift`). The launch argument avoids the
SpringBoard "Open in …?" confirmation dialog that `simctl openurl
sceneview://demo/<id>` raises; the `sceneview://` URL scheme remains the
user-facing deep link (scan a QR code → land on a demo).

Override the simulators or settle time with env vars:

```bash
IPHONE_SIM="iPhone 17 Pro Max" IPAD_SIM="iPad Pro 13-inch (M5)" \
  WAIT_SECONDS=28 bash .claude/scripts/capture-appstore-screenshots.sh
```

### The system-banner guard

`simctl` exposes no way to silence notifications, and waiting them out does not
work either: a freshly-erased device posts "Ready for Apple Intelligence" about
a minute into the session — i.e. *during* a capture, not before the first one —
which is exactly how that card landed in an iPad frame.

So the script detects it. Because `-qa_mode 1` freezes the scene, the top band
of a frame is static; the script shoots each demo, then re-shoots every
`BANNER_RECHECK_SECONDS` (8 s) until it has `BANNER_SAMPLES` (3) samples, and
compares a hash of that band across all of them. All equal means nothing
transient was drawn over it. Any difference means a banner was up in one of them
— the set is discarded and retried, up to `BANNER_MAX_ATTEMPTS` (4), after which
the frame is **deleted** and the run **fails** rather than leaving a
contaminated PNG in the tree.

⚠️ What it proves is that the band did not *change* — not that it is clean. An
overlay already up at the first shot that outlives the whole ~16 s sampling
window hashes identically every time and is accepted. Sampling wider shrinks
that blind spot; it never closes it.

So it is a guard, not a substitute for looking. **Open all four PNGs before
committing them.** #917 shipped a set that passed every mechanical check and was
still wrong (Android captures letterboxed onto an iPad canvas, blank AR scenes),
and #2896 nearly shipped a "park diorama" that was actually a piano.

## Publishing these to the App Store

The upload path exists (#2612 Phase B) — no fastlane involved:

```bash
# What differs between this directory and the live listing (read-only):
python3 .claude/scripts/store-sync/asc_listing.py --dry-run

# Push these screenshots to the EDITABLE App Store version:
python3 .claude/scripts/store-sync/asc_listing.py --apply-screenshots
```

From CI: run the **`Sync App Store screenshots`** workflow
(`.github/workflows/app-store-screenshots.yml`) with `confirm=true`.

Three things worth knowing before you run it:

- It targets the **editable** version and never creates one. With no editable
  version it skips (loudly) rather than inventing somewhere to write.
- It **replaces** a display type's whole set (delete-then-upload) when anything
  differs. Live order is *expected* to follow this directory's filename order —
  Apple does not promise that a set preserves creation order, so the script
  probes the live order after uploading and warns if it diverges. Untouched
  display types are left alone.
- It is deliberately **not** part of `app-store.yml`. A dispatch there also
  runs `deploy-ios`/`deploy-macos`, which archive and upload a TestFlight
  build — refreshing screenshots must not do that.

Screenshots persist from one App Store version to the next, so this is
listing maintenance rather than a per-release step; nothing runs it on a tag.
