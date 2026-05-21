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

4-tab showcase with 14 interactive demos:

- **3D tab**: 8 models, 6 HDR environments, orbit camera, animations
- **AR tab**: Tap-to-place, plane detection, 4 AR models, gesture controls
- **Samples tab**: Model viewer, geometry, animation, dynamic sky demos
- **About tab**: Platform info, GitHub links

```bash
./gradlew :samples:android-demo:assembleDebug
```

!!! info "In-app feedback"
    The Android demo includes an opt-in **feedback button** for reporting a bug
    or sharing an idea. When used, it records the screen and microphone and
    uploads them to the SceneView feedback service, which transcribes the audio
    and opens a pre-filled GitHub issue. The feature is consent-gated, the raw
    recording stays private and is deleted after 90 days, and only the
    transcript + device context appear on the public issue. See the
    [Privacy Policy](privacy.md) for the full data flow.

### iOS Demo

**`samples/ios-demo/`** — App Store ready, SwiftUI

3-tab SwiftUI app:

- **3D tab**: RealityKit model viewer with environment controls
- **AR tab**: ARKit surface detection and model placement
- **Samples tab**: Feature gallery

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
| AR Tap-to-Place | `samples/recipes/ar-tap-to-place.md` | Plane detection, anchor placement |
| Physics | `samples/recipes/physics.md` | Rigid body, gravity, collision, bounce |
| Procedural Geometry | `samples/recipes/procedural-geometry.md` | Cubes, spheres, custom shapes |
| Text Labels | `samples/recipes/text-labels.md` | 3D text, billboards, tap interaction |

---

!!! tip "Looking for Apple-specific samples?"
    See [Samples — Apple Platforms](samples-ios.md) for SwiftUI + RealityKit examples on iOS, macOS, and visionOS.
