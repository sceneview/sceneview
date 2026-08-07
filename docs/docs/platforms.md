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
| **Android** | Filament | Jetpack Compose | `sceneview` / `arsceneview` | Stable (v4.0.0) |
| **iOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.0.0) |
| **macOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.0.0) |
| **visionOS** | RealityKit | SwiftUI | `SceneViewSwift` | Alpha (v4.0.0) |
| **Web** | Filament.js (WASM) | Kotlin/JS | `sceneview-web` | Alpha |
| **Desktop** | Software wireframe (placeholder) | Compose Desktop | `samples/desktop-demo` | Placeholder (not SceneView) |
| **Android TV** | Filament | Compose TV | `sceneview` | Alpha |
| **Flutter** | Filament / RealityKit | PlatformView | `flutter/sceneview_flutter` | Alpha |
| **React Native** | Filament / RealityKit | Fabric | `react-native/react-native-sceneview` | Alpha |
| **Compose Multiplatform** | per-platform | Compose Multiplatform | `sceneview-compose` | Android + iOS implemented; Desktop placeholder |

!!! note "Compose Multiplatform is a façade, not a platform"
    `sceneview-compose` gives you one `SceneViewer` composable from `commonMain` and
    delegates to the renderers above. It covers the **viewer subset** only — a model, an
    orbit camera, a light, an environment, tap hit-testing. **No AR**, no custom
    materials, no post-processing: those stay platform-native by design. Today Android
    renders; the iOS and Desktop actuals draw a visible "not available yet" notice.
    See [Compose Multiplatform](compose-multiplatform.md).

---

## Android

The primary platform. SceneView wraps Google Filament (PBR rendering) and ARCore (augmented reality) in Jetpack Compose composables.

- **3D**: `SceneView { }` composable with 46+ node types
- **AR**: `ARSceneView { }` with plane detection, image tracking, face mesh, cloud anchors, geospatial
- **Min SDK**: 24 (Android 7.0)
- **Install**: `implementation("io.github.sceneview:sceneview:4.26.0")`

[:octicons-arrow-right-24: Android Quickstart](quickstart.md)

---

## iOS / macOS / visionOS

SceneViewSwift provides a native SwiftUI library powered by RealityKit and ARKit. Distributed as a Swift Package.

- **3D**: `SceneView { }` with ModelNode, GeometryNode, LightNode, and more
- **AR**: `ARSceneView()` with plane detection and tap-to-place (iOS only)
- **Min versions**: iOS 18+, macOS 15+, visionOS 1+
- **Install**: `.package(url: "https://github.com/sceneview/sceneview.git", from: "4.26.0")`

[:octicons-arrow-right-24: Apple Quickstart](quickstart-ios.md)

---

## Web

SceneView Web uses **Filament.js** -- the same Filament rendering engine as Android, compiled to WebAssembly for browsers (WebGL2).

- **Rendering**: Same PBR quality as Android
- **WebXR**: AR/VR support via WebXR API
- **Format**: glTF 2.0 / GLB (same as Android)
- **Install**: `npm install @sceneview/sceneview-web` or use the Kotlin/JS Gradle module

[:octicons-arrow-right-24: Web Quickstart](quickstart-web.md)

---

## Desktop (Placeholder)

> **Not SceneView.** The desktop demo is a Compose Canvas wireframe renderer -- it does
> not use SceneView or Filament. It exists as a UI placeholder for a future Filament JNI
> desktop integration.

- **Renderer**: Software wireframe (Compose Canvas 2D drawing, not GPU-accelerated)
- **Framework**: Compose Desktop
- **Sample**: `samples/desktop-demo/`
- **Missing**: GPU acceleration, PBR materials, glTF loading, shadows, scene graph

A future version would use Filament JNI for full PBR rendering. This requires building
Filament from source with JNI enabled (estimated 18-29 days). See
[Filament Desktop Research](desktop-filament.md) for details.

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
- **Install**: `flutter_sceneview: ^4.24.0` in pubspec.yaml ([pub.dev](https://pub.dev/packages/flutter_sceneview) — the packages named `sceneview` / `sceneview_flutter` are unrelated third-party uploads)

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
