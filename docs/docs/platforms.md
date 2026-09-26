---
title: Supported Platforms — SceneView 3D & AR SDK
description: "SceneView supports 9+ platforms: Android, iOS, macOS, visionOS, Web, Desktop, Android TV, Flutter, and React Native. Native renderers per platform."
---

# Supported Platforms

SceneView uses **native renderers per platform** for the best performance and tooling on each target. Shared logic (math, collision, geometry, animations) lives in `sceneview-core` via Kotlin Multiplatform.

---

## Platform Overview

| Platform | Renderer | Framework | Module | Status |
|---|---|---|---|---|
| **Android** | Filament | Jetpack Compose | `sceneview` / `arsceneview` | Stable (v4.39.0) |
| **iOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.39.0) |
| **macOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.39.0) |
| **visionOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.39.0) |
| **Web** | Filament.js (WASM) | Kotlin/JS | `sceneview-web` | Alpha |
| **Desktop** | Filament via `SceneViewer` (filament-kmp) | Compose Desktop | `samples/desktop-demo` | Alpha (JDK 22+) |
| **Android TV** | Filament | Compose TV | `sceneview` | Alpha |
| **Flutter** | Filament / RealityKit | PlatformView | `flutter/sceneview_flutter` | Alpha |
| **React Native** | Filament / RealityKit | Fabric | `react-native/react-native-sceneview` | Alpha |
| **Compose Multiplatform** | per-platform | Compose Multiplatform | `sceneview-compose` | Android, iOS, Desktop implemented |

!!! note "Compose Multiplatform is a façade, not a platform"
    `sceneview-compose` gives you one `SceneViewer` composable from `commonMain` and
    delegates to the renderers above. It covers the **viewer subset** only — a model, an
    orbit camera, a light, an environment, tap hit-testing. **No AR**, no custom
    materials, no post-processing: those stay platform-native by design. Android and
    Desktop render through Filament; iOS renders through RealityKit once the app
    registers the one-time host factory, and draws a visible "not available yet" notice
    until it does. See [Compose Multiplatform](compose-multiplatform.md).

---

## Android

The primary platform. SceneView wraps Google Filament (PBR rendering) and ARCore (augmented reality) in Jetpack Compose composables.

- **3D**: `SceneView { }` composable with 48+ node types
- **AR**: `ARSceneView { }` with plane detection, image tracking, face mesh, cloud anchors, geospatial
- **Min SDK**: 24 (Android 7.0)
- **Install**: `implementation("io.github.sceneview:sceneview:4.40.0")`

[:octicons-arrow-right-24: Android Quickstart](quickstart.md)

---

## iOS / macOS / visionOS

SceneViewSwift provides a native SwiftUI library powered by RealityKit and ARKit. Distributed as a Swift Package.

- **3D**: `SceneView { }` with ModelNode, GeometryNode, LightNode, and more
- **AR**: `ARSceneView()` with plane detection and tap-to-place (iOS only)
- **Min versions**: iOS 18+, macOS 15+, visionOS 2+
- **Install**: `.package(url: "https://github.com/sceneview/sceneview.git", from: "4.40.0")`

[:octicons-arrow-right-24: Apple Quickstart](quickstart-ios.md)

---

## Web

SceneView Web uses **Filament.js** -- the same Filament rendering engine as Android, compiled to WebAssembly for browsers (WebGL2).

- **Rendering**: Same PBR quality as Android
- **WebXR**: AR/VR support via WebXR API
- **Format**: glTF 2.0 / GLB (same as Android)
- **Install**: `npm install sceneview-web` or use the Kotlin/JS Gradle module

[:octicons-arrow-right-24: Web Quickstart](quickstart-web.md)

---

## Desktop (Compose Desktop)

The desktop actual of `sceneview-compose` renders with **Filament**, through the
community [filament-kmp](https://github.com/Erkko68/filament-kmp) FFM bindings: an
offscreen render, pipelined `readPixels`, then a Skia image in the Compose tree.

- **3D**: `SceneViewer(…)` — the same viewer subset as the other Compose Multiplatform
  targets (glTF model, orbit camera, light, environment, tap hit-testing). **No AR.**
- **Framework**: Compose Desktop
- **Requirements**: JDK 22+ (FFM), launched with `--enable-native-access=ALL-UNNAMED`
- **Install**: `implementation("io.github.sceneview:sceneview-compose:4.40.0")`
- **Sample**: `samples/desktop-demo/` — run it with `./gradlew :samples:desktop-demo:run`

[:octicons-arrow-right-24: Compose Multiplatform](compose-multiplatform.md) ·
[Desktop Filament decision record](desktop-filament.md)

---

## Android TV

SceneView works on Android TV using the same Filament renderer as mobile. The `SceneView { }` composable renders identically -- only the input handling differs (D-pad instead of touch).

- **Input**: D-pad controls (orbit, zoom, model cycling)
- **UI**: Lean-back 10-foot interface
- **Install**: Same `sceneview` dependency as mobile

[:octicons-arrow-right-24: TV Quickstart](quickstart-tv.md)

---

## Flutter

A Flutter plugin that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit) via PlatformView.

- **Android**: `ComposeView` hosting `SceneView { }` composable
- **iOS**: `SceneViewerHostView`, the shared `SceneViewSwift` host, for the 3D path; AR keeps its own platform view
- **Install**: `flutter_sceneview: ^4.39.0` in pubspec.yaml ([pub.dev](https://pub.dev/packages/flutter_sceneview) — the packages named `sceneview` / `sceneview_flutter` are unrelated third-party uploads)

[:octicons-arrow-right-24: Flutter Quickstart](quickstart-flutter.md)

---

## React Native

A React Native module that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit) via Fabric components.

- **Android**: `SimpleViewManager` with `ComposeView` hosting `SceneView { }`
- **iOS**: `RCTViewManager` with `SceneViewerHostView`, the shared `SceneViewSwift` host, for the 3D path; AR keeps its own platform view
- **Install**: `npm install @sceneview-sdk/react-native`

[:octicons-arrow-right-24: React Native Quickstart](quickstart-react-native.md)

---

## Architecture

```text
+-------------------------------------------------+
|              sceneview-core (KMP)                |
|     math, collision, geometry, animations        |
|         commonMain -> XCFramework                |
+----------+---------------------+-----------------+
           |                     |
    +------v------+       +------v------+
    |  sceneview  |       |SceneViewSwift|
    |  (Android)  |       |   (Apple)    |
    |  Filament   |       |  RealityKit  |
    +------+------+       +------+------+
           |                     |
     Compose UI           SwiftUI (native)
     Compose TV           Flutter (PlatformView)
     Filament.js (Web)    React Native (Fabric)
     Compose Desktop      KMP Compose (UIKitView)
```

**Key decision:** KMP shares **logic** (math, collision, geometry, animations), not **rendering**. Each platform uses its native renderer for the best performance, tooling, and platform integration.

[:octicons-arrow-right-24: Full Architecture Guide](architecture.md)
