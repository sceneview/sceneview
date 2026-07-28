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

## Demos captured — the common Android↔iOS showcase set v2 (#2854, #2896)

The **same three demos, in the same order**, as Android's
`capture-play-store-screenshots.sh`, so both stores show identical screens.

The scene-side RealityKit blockers that had deferred this refresh — dim
lighting, far default framing, and `dynamic-sky` rendering no sky at all — are
**fixed** by #2896: the custom IBL and the skybox now load, and `framingMargin`
and `cameraOrbit` give the capture the two levers #2785 said did not exist. The
frames below were shot against that fix and are committed here.

⚠️ **The committed PNGs are not the live listing.** Committing them does not
upload them — the App Store listing keeps showing the old set until someone runs
`store-sync/asc_listing.py --apply-screenshots` (or uploads through App Store
Connect). Until that happens, do not describe the two stores as in sync.

What is committed today (set v2, in this order):

1. `01-model-viewer` — bundled hero model (cyberpunk hovercar) on the `.warm`
   photo-studio backdrop, frozen on a three-quarter hero pose
2. `02-dynamic-sky` — procedural time-of-day skyline under a live HDRI sky
3. `03-multi-model` — several streamed/bundled models composed into one scene

Three ids that earlier sets carried were dropped on both platforms, for defects
that are platform-independent — worth knowing before anyone re-adds them here.
`materials` opened on a streamed subject and drew an orbiting HDRI skybox, so
neither the subject nor the backdrop was reproducible; the demo side is fixed
(#2874) and the id is eligible again, but do not re-add it without capturing it
and looking at the frame against the other slots first. `geometry` clips its
primitives in a portrait frame (#2873 — measured on Android; not re-measured on
iOS). `double-pendulum` renders as a tiny linkage in a mostly-black frame.

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
of a frame is static; the script shoots each demo, waits
`BANNER_RECHECK_SECONDS` (8 s), shoots again, and compares a hash of that band.
Equal means nothing transient was drawn over it. Different means a banner was up
in one of the two — the pair is discarded and retried, up to
`BANNER_MAX_ATTEMPTS` (4), after which the run **fails** rather than committing
a contaminated frame.

It is a guard, not a substitute for looking. **Open all six PNGs before
committing them.** #917 shipped a set that passed every mechanical check and was
still wrong (Android captures letterboxed onto an iPad canvas, blank AR scenes).

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
