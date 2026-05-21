# SceneView Flutter Demo

> Feature showcase for the SceneView Flutter bridge.
>
> The Flutter bridge exposes a **subset** of the native SceneView SDK. This
> demo honestly surfaces what does and does not work today — see
> [Bridge coverage](#bridge-coverage) and issue
> [#909](https://github.com/sceneview/sceneview/issues/909).

## Tabs

| Tab | Demonstrates |
|---|---|
| **3D Viewer** | SceneView widget, Sketchfab search, model loading (GLB/glTF), rotation/scale sliders, onTap callback |
| **AR** | ARSceneView widget, plane detection, onPlaneDetected callback, AR/3D mode toggle, model placement |
| **Features** | Every bridge API with code snippets, live geometry demo, live light demo |
| **About** | Architecture diagram, supported features checklist, version info |

## Architecture

```
Flutter (Dart)
  +-- PlatformView --> Android: SceneView (Filament via Jetpack Compose)
  +-- PlatformView --> iOS: SceneViewSwift (RealityKit via SwiftUI)
```

## Bridge Features Demonstrated

- **SceneView** / **ARSceneView** widgets (PlatformView)
- **SceneViewController** (loadModel, clearScene, setEnvironment, addLight, addGeometry)
- **ModelNode** with position, rotation (X/Y/Z), scale
- **onTap** callback (node name)
- **onPlaneDetected** callback (plane type)
- **GeometryNode** (cube, sphere, cylinder, plane)
- **LightNode** (directional, point, spot)
- **Environment HDR** (image-based lighting)
- **Sketchfab search** (public API, no key required)
- **AR/3D mode toggle**

## Bridge coverage

The Flutter bridge is alpha and exposes only part of the SceneView SDK. The
**Features** tab in the app shows a per-API status badge; this table mirrors it:

| Feature | Real bridge status |
|---|---|
| `loadModel`, `clearScene` | Works on Android and iOS |
| `ModelNode` position / rotation | Android only; iOS honours path + scale (#909) |
| `addGeometry`, `addLight` | Rendered on **Android** only; iOS RealityKit port pending (#909) |
| `setEnvironment` (HDR IBL) | Works on Android; iOS acknowledges the call but does not apply it (#909) |
| `onTap`, `onPlaneDetected` | Forwarded on Android; not yet forwarded on iOS (#909) |
| `cameraControlMode` `pan` / `firstPerson` | iOS-only; fall back to orbit on Android |

Tracked in the [#909](https://github.com/sceneview/sceneview/issues/909)
bridge-parity umbrella.

## Run

```bash
cd samples/flutter-demo
flutter pub get
flutter run
```

## Integration Tests

```bash
cd samples/flutter-demo
flutter test integration_test/screenshot_test.dart
```

## Requirements

- Flutter 3.10+
- Android SDK 24+ (for Android)
- iOS 17+ (for iOS)
- `http` package (for Sketchfab search)
