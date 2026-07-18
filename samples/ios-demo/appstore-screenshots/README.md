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

## Demos captured — the common Android↔iOS showcase set (#2773)

The **same five demos, in the same order**, as Android's
`capture-play-store-screenshots.sh`, so both stores show identical screens.
Every id is a standalone (non-consolidated) demo on both platforms and renders
rich 3D content — deliberately *not* empty or loading AR scenes:

1. `01-model-viewer` — bundled hero model (cyberpunk hovercar), orbit camera
2. `02-lighting` — PBR-lit spheres with the light-type switcher
3. `03-materials` — metallic/roughness material showcase
4. `04-geometry` — generated geometry primitives
5. `05-double-pendulum` — animated physics (motion)

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

## Follow-up — CI auto-upload

Wiring these into the App Store deploy pipeline (`fastlane deliver` in
`.github/workflows/app-store.yml`) is a **deliberate follow-up**, tracked
separately. This directory + the capture script give a reproducible source
of truth; the upload automation is the next step.
