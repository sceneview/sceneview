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

## Demos captured — pre-v2 set, refresh deferred (#2896)

⚠️ **The committed PNGs are one set behind, and the two stores are not in sync.**
`capture-appstore-screenshots.sh` already shoots **set v2** — `model-viewer ·
dynamic-sky · multi-model`, the three frames Android moved to in #2854/#2855 —
but the iOS images have not been re-captured, because that is blocked on
scene-side RealityKit fixes: dim lighting, far default framing, and
`dynamic-sky` rendering no sky at all (#2896; no capture-side lever exists,
see #2785). Do not describe the listings as identical.

What is committed today (the pre-v2 five, in this order):

1. `01-model-viewer` — bundled hero model (cyberpunk hovercar), orbit camera
2. `02-lighting` — PBR-lit spheres with the light-type switcher
3. `03-materials` — metallic/roughness material showcase
4. `04-geometry` — generated geometry primitives
5. `05-double-pendulum` — animated physics (motion)

Three of those ids were retired from the Android set for defects that are
platform-independent, and worth knowing before re-capturing here: `materials`
picks a different HDRI *and* model per launch, so the frame is not reproducible
(#2874); `geometry` clips its primitives in a portrait frame (#2873); and
`double-pendulum` renders as a tiny linkage in a mostly-black frame.

Captured in **dark appearance** with a cleaned status bar (fixed 9:41, full
signal/battery), mirroring the Android capture's dark-mode + status-bar crop
so the two stores match visually.

> **Note:** the previously-committed PNGs in this directory
> (`reflection-probes / occlusion-material / geometry-primitives /
> samples-catalog`, plus only 2 iPad shots) predate this set and are stale —
> regenerate with the command below once an iOS simulator build is feasible.

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
