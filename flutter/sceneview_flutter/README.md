# sceneview_flutter

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub](https://img.shields.io/badge/GitHub-sceneview%2Fsceneview-black)](https://github.com/sceneview/sceneview)

Flutter plugin for [SceneView](https://sceneview.github.io) — 3D and AR scenes using native renderers.

| Platform | Renderer                          | Status                          |
|----------|-----------------------------------|---------------------------------|
| Android  | Filament (via Jetpack Compose)    | Alpha — 3D model loading works  |
| iOS      | RealityKit (via SceneViewSwift)   | Alpha — 3D model loading works  |

## Features

- Load and display 3D models (GLB/GLTF) using native renderers
- AR scenes with plane detection on Android (ARCore) and iOS (ARKit)
- HDR environment lighting (Android; iOS support pending — #909)
- Orbit camera controls (touch gestures)
- `SceneViewController` for imperative commands
- Geometry and light node APIs — rendered natively on **Android**; the iOS
  RealityKit port is not yet bridged (#909)

> This plugin exposes a **subset** of the SceneView SDK. See the
> [Controller API](#controller-api) note and issue
> [#909](https://github.com/sceneview/sceneview/issues/909) for the full
> coverage map.

## Installation

> **Status:** the plugin is not yet on pub.dev for the 4.0 line. The pub.dev
> registry still holds an unrelated 0.0.1 demo from an earlier prototype. Add the
> plugin as a Git dependency for now — pub.dev publishing for `4.0.x` is tracked
> in [#923](https://github.com/sceneview/sceneview/issues/923).

In your `pubspec.yaml`:

```yaml
dependencies:
  sceneview_flutter:
    git:
      url: https://github.com/sceneview/sceneview
      path: flutter/sceneview_flutter
      ref: v4.0.9   # any released `v[0-9]+.[0-9]+.[0-9]+` tag
```

Then run:

```sh
flutter pub get
```

### Android setup

Minimum SDK 24. In `android/app/build.gradle`:

```groovy
android {
    defaultConfig {
        minSdkVersion 24
    }
}
```

### iOS setup

Minimum iOS 17. In `ios/Podfile`:

```ruby
platform :ios, '17.0'
```

The host app must also add `SceneViewSwift` via Swift Package Manager in Xcode:
- URL: `https://github.com/sceneview/SceneViewSwift`
- Version: `3.6.2`

## Usage

### 3D Scene

```dart
import 'package:sceneview_flutter/sceneview_flutter.dart';

final controller = SceneViewController();

SceneView(
  controller: controller,
  onViewCreated: () {
    controller.setEnvironment('environments/studio_small.hdr');
    controller.loadModel(const ModelNode(modelPath: 'models/damaged_helmet.glb'));
  },
)
```

### AR Scene

```dart
import 'package:sceneview_flutter/sceneview_flutter.dart';

final controller = SceneViewController();

ARSceneView(
  controller: controller,
  planeDetection: true,
  onViewCreated: () {
    controller.loadModel(const ModelNode(modelPath: 'models/andy.glb'));
  },
)
```

### Controller API

| Method                                | Description                                              |
|----------------------------------------|----------------------------------------------------------|
| `loadModel(ModelNode)`                 | Load a glTF/GLB model into the scene                     |
| `clearScene()`                         | Remove all models from the scene                         |
| `setEnvironment(String path)`          | Set HDR environment for image-based lighting (Android; iOS accepts but does not apply it — #909) |
| `addGeometry(GeometryNode)`            | Add a geometry node — rendered on **Android**; iOS port pending (#909) |
| `addLight(LightNode)`                  | Add a light node — rendered on **Android**; iOS port pending (#909) |
| `setCameraControlMode(CameraControlMode)` | Change the camera mode at runtime (v4.3.0)            |
| `setAutoCenterContent(bool)`           | Toggle content auto-centring at runtime (v4.3.0)         |
| `isAttached`                           | Whether the controller is attached to a live view        |

> ⚠️ **Bridge coverage.** This plugin exposes a subset of the native SceneView
> SDK. `addGeometry` / `addLight` render natively on Android only; node taps,
> plane events and the HDR environment are forwarded on Android but not yet on
> iOS. Camera positioning, `ViewNode` / `ImageNode` / `VideoNode` / `TextNode`,
> advanced AR anchors and the `sceneview-core` physics/geometry APIs are not
> bridged at all. The full gap is tracked in the
> [#909](https://github.com/sceneview/sceneview/issues/909) umbrella.

### Camera controls & content centring (v4.3.0)

`SceneView` accepts a `cameraControlMode` and `autoCenterContent`:

```dart
SceneView(
  controller: controller,
  cameraControlMode: CameraControlMode.pan, // .orbit | .pan | .firstPerson
  autoCenterContent: false,                 // default true
)
```

`CameraControlMode.pan` and `.firstPerson` are iOS-only in v4.3.0; on Android
they fall back to orbit. `autoCenterContent` is iOS-first — the Android side
is tracked in issue #1051.

### AR recording (v4.3.0 — iOS)

`ARRecorder` records an AR session to a `.mov` video (iOS via ReplayKit):

```dart
final recorder = ARRecorder(arController);

await recorder.startRecording();
// ... later ...
final path = await recorder.stopRecording();
await recorder.saveToPhotoLibrary(path);
```

`ARRecorder` is iOS-only; on Android every method throws an `UnsupportedError`
(ARCore session recording is tracked in issue #1051). The host iOS app must
declare `NSPhotoLibraryAddUsageDescription` in `Info.plist` to use
`saveToPhotoLibrary`.

### ModelNode properties

| Property    | Type     | Default | Description                         |
|-------------|----------|---------|-------------------------------------|
| `modelPath` | `String` | —       | Asset path or URL to GLB/GLTF file  |
| `x`         | `double` | `0.0`   | X position in world space           |
| `y`         | `double` | `0.0`   | Y position in world space           |
| `z`         | `double` | `0.0`   | Z position in world space           |
| `scale`     | `double` | `1.0`   | Uniform scale factor                |

## Architecture

```
Flutter (Dart)
  |
  +-- PlatformView -----> Android: ComposeView + SceneView { }
  |                        (Filament renderer, SceneView SDK 3.6.2)
  |
  +-- PlatformView -----> iOS: UIHostingController + SceneViewSwift
                           (RealityKit renderer, SceneViewSwift 3.6.2)
```

Method channels bridge Dart commands (`loadModel`, `clearScene`, `setEnvironment`) to native implementations.

## Limitations

- Geometry and light nodes are not yet rendered natively (API exists for forward compatibility)
- AR tap-to-place is not yet implemented
- No event callbacks from native to Dart yet (`onTap`, `onModelLoaded`)
- Only Android and iOS are supported; other platforms show a fallback message

## Contributing

See [CONTRIBUTING.md](https://github.com/sceneview/sceneview/blob/main/.github/CONTRIBUTING.md).

## License

Apache-2.0 — see [LICENSE](LICENSE) for details.
