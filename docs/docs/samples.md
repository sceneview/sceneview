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

4-tab showcase (**Explore / AR View / Samples / About**) backed by an
append-only demo registry of **50 demos** (17 non-AR + 33 AR):

- **Explore tab**: Featured 3D & AR demos and multi-source model streaming (Sketchfab / Icosa Gallery / Poly Haven)
- **AR View tab**: Live `ARSceneView` camera with plane detection and tap-to-place
- **Samples tab**: The full demo catalog, grouped into six categories —
  3D Basics, Lighting & Environment, Content, Interaction, Advanced,
  Augmented Reality
- **About tab**: Platform info, version, and GitHub links

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
  Poly Haven (#2700, parity with the Android Explore tab). Browse + search work
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
- WebXR AR/VR support ("Enter AR" / "Enter VR" buttons)
- Orbit camera, auto-resize

A plain static site (HTML + inline JS + a self-hosted `sceneview.js`) —
not a Gradle module. Open `samples/web-demo/site/index.html` directly, or
serve the folder:

```bash
npx http-server samples/web-demo/site -p 8080
```

### Desktop Demo

**`samples/desktop-demo/`** — Compose Desktop (Software Wireframe Placeholder)

> **Note:** This demo does **not** use SceneView or Filament. It is a Compose Canvas
> wireframe renderer that serves as a UI placeholder for a future Filament JNI integration.

- Rotating wireframe cube, octahedron, diamond (Canvas 2D drawing, not GPU-accelerated)
- Manual perspective projection with basic trigonometry
- Material 3 dark theme

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
- iOS: UIHostingController + SceneViewSwift

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
| AR Tap-to-Place | `samples/recipes/ar-tap-to-place.md` | Plane detection, anchor placement |
| Physics | `samples/recipes/physics.md` | Rigid body, gravity, collision, bounce |
| Procedural Geometry | `samples/recipes/procedural-geometry.md` | Cubes, spheres, custom shapes |
| Text Labels | `samples/recipes/text-labels.md` | 3D text, billboards, tap interaction |

---

!!! tip "Looking for Apple-specific samples?"
    See [Samples — Apple Platforms](samples-ios.md) for SwiftUI + RealityKit examples on iOS, macOS, and visionOS.
