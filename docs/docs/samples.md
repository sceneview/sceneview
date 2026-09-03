---
title: Samples — SceneView
description: "Platform demo apps and code recipes for SceneView: Android, iOS, Web, Desktop, Android TV, Flutter, React Native."
---

# Samples

One unified showcase app per platform. Clone the repo and run:

```bash
git clone https://github.com/sceneview/sceneview.git
```

---

## Platform Demos

### Android Demo

**`samples/android-demo/`** — Play Store ready, Material 3 Expressive

3-tab showcase (**Showcase / AR View / About**) backed by an
append-only demo registry of **50 demos** (18 non-AR + 32 AR), grouped into nine
catalogue sections:

- **Showcase tab** (home): a hero card, category filter chips and a grid of media
  cards with a generated preview image per demo, split by a full-span section header
  into **Viewer · Geometry & Materials · Rendering · Interaction · AR Placement ·
  AR Tracking · AR Understanding · AR Anchors · Platform**. The section order is
  `DEMO_CATEGORIES` in `DemoRegistry.kt` and the chips filter down to one section.
  The closing "Browse online models" card opens the online gallery — multi-source
  model streaming (Sketchfab / Icosa Gallery / Poly Haven)
- **AR View tab**: Live `ARSceneView` camera with plane detection and tap-to-place
- **About tab**: Platform info, version, and GitHub links

Every demo screen shares the same glass chrome: back button, identity pill and
overflow menu over the scene, plus a floating bottom dock whose Controls item opens
the settings sheet.

Each demo is one append-only `*Fragment.kt` under
`samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/` and is
deep-link addressable as `sceneview://demo/<id>`. The collator
(`samples/android-demo/scripts/collate-demos.sh`) generates the demo registry
and the `llms.txt` demo list from those fragments, so the catalog never drifts.

```bash
./gradlew :samples:android-demo:assembleDebug
```

!!! info "In-app bug reports"
    The Android demo includes a **feedback button** that opens a lightweight
    "Report a bug" sheet — an optional `PixelCopy` screenshot of the app, the
    app's own logcat tail, and device/app context. It needs **zero
    permissions** (no recording, no foreground service) and never uploads
    anything itself: the user sends the report through the Android share
    sheet, or opens a pre-filled GitHub issue in the browser. See the
    [Privacy Policy](privacy.md) for the full data flow.

### iOS Demo

**`samples/ios-demo/`** — App Store ready, SwiftUI

4-tab SwiftUI app:

- **Explore tab**: multi-source model streaming — Sketchfab / Icosa Gallery /
  Poly Haven (#2700, parity with the Android online gallery). Browse + search work
  for every catalog; in-app 3D rendering is USDZ-based (Sketchfab) today — the
  glTF-native CC catalogs show an honest "3D preview coming soon" viewer state
  until RealityKit can load glTF.
- **AR tab**: ARKit surface detection and model placement
- **Samples tab**: Feature gallery
- **About tab**: Platform info, version, and GitHub links

Open `samples/ios-demo/` in Xcode and run.

### Web Demo

**`samples/web-demo/`** — Filament.js + WebXR

Browser 3D viewer with:

- Filament.js WASM rendering (same engine as Android)
- Models tab: source-agnostic multi-source catalog (#2722, parity with the
  Android online gallery / iOS Explore) — curated SceneView samples, Icosa Gallery and
  Poly Haven (keyless CC catalogs, rendered in-app), Sketchfab when an API
  key is configured
- WebXR AR/VR support ("Enter AR" / "Enter VR" buttons)
- Orbit camera, auto-resize

A plain static site (HTML + inline JS + a self-hosted `sceneview.js`) —
not a Gradle module. Open `samples/web-demo/site/index.html` directly, or
serve the folder:

```bash
npx http-server samples/web-demo/site -p 8080
```

### Desktop Demo

**`samples/desktop-demo/`** — Compose Desktop (`SceneViewer`)

`sceneview-compose` `SceneViewer` on JVM. Filament via filament-kmp (offscreen →
Skia). **JDK 22+**.

- Loads `Duck.glb` through `ModelSource.Bytes`
- Drag to orbit, scroll to zoom

```bash
./gradlew :samples:desktop-demo:run
```

### Android TV Demo

**`samples/android-tv-demo/`** — Compose TV

D-pad controlled 3D viewer:

- Model cycling with directional buttons
- Auto-rotation
- Lean-back UI

```bash
./gradlew :samples:android-tv-demo:assembleDebug
```

### Flutter Demo

**`samples/flutter-demo/`** — PlatformView bridge

Native SceneView rendering inside Flutter:

- Android: ComposeView + Scene composable
- iOS: SceneViewerHostView + SceneViewSwift (the shared 3D host; AR keeps its own view)

```bash
cd samples/flutter-demo && flutter run
```

### React Native Demo

**`samples/react-native-demo/`** — Fabric bridge

Native SceneView rendering inside React Native:

- Android: ViewManager + Scene composable
- iOS: RCTViewManager + SceneViewSwift

---

## Code Recipes

Markdown recipes with side-by-side Kotlin and Swift code:

| Recipe | File | Topics |
|---|---|---|
| Model Viewer | `samples/recipes/model-viewer.md` | Load glTF, HDR environment, orbit camera |
| Ground Shadow Catcher | `samples/recipes/ground-shadow-catcher.md` | Invisible contact-shadow floor, FL2+ flat-quad hardening |
| Contact Shadow | `samples/recipes/contact-shadow.md` | Procedural grounding pool — works on walls (Floor / Wall / TableTop) |
| AR Tap-to-Place | `samples/recipes/ar-tap-to-place.md` | Plane detection, anchor placement |
| Point & Ask | `samples/recipes/point-and-ask.md` | AR frame capture, Gemini Nano on-device (ML Kit GenAI Prompt API), availability gating, answers anchored in world space |
| Physics | `samples/recipes/physics.md` | Rigid body, gravity, collision, bounce |
| Procedural Geometry | `samples/recipes/procedural-geometry.md` | Cubes, spheres, custom shapes |
| Text Labels | `samples/recipes/text-labels.md` | 3D text, billboards, tap interaction |

---

!!! tip "Looking for Apple-specific samples?"
    See [Samples — Apple Platforms](samples-ios.md) for SwiftUI + RealityKit examples on iOS, macOS, and visionOS.
